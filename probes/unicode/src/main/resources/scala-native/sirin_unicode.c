#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include "WordSegmenter.h"
#include "WordBreakIteratorUtf8.h"
#include "Bidi.h"
#include "BidiInfo.h"
#include "BidiParagraph.h"
#include "ReorderedIndexMap.h"

/* Diplomat slice/option structs and fallible results never cross into Scala.
 * This isolated probe has ICU4X as its only Rust dependency. No Scala callbacks. */
typedef struct {
  WordSegmenter *words;
  Bidi *bidi;
} SirinUnicode;
/* Bounded C ABI for pinned Unicode 18 algorithms missing from generated C API. */
extern int32_t su_unicode18_breaks(const uint8_t*, size_t, int32_t, int32_t*, size_t);
_Static_assert(sizeof(int) == sizeof(int32_t), "Unicode boundary ABI requires 32-bit int");
typedef struct {
  int counts[5]; /* grapheme, word, line boundaries; bytes; paragraphs */
  int *graphemes, *words, *lines, *word_types, *paragraphs;
  uint8_t *levels;
} SirinUnicodeResult;

void su_close(void *raw) {
  SirinUnicode *u = raw;
  if (!u) return;
  if (u->words) icu4x_WordSegmenter_destroy_mv1(u->words);
  if (u->bidi) icu4x_Bidi_destroy_mv1(u->bidi);
  free(u);
}
int su_open(void **out) {
  *out = NULL;
  SirinUnicode *u = calloc(1, sizeof(*u));
  if (!u) return 1;
  u->words = icu4x_WordSegmenter_create_for_non_complex_scripts_mv1();
  u->bidi = icu4x_Bidi_create_mv1();
  if (!u->words || !u->bidi) { su_close(u); return 3; }
  *out = u;
  return 0;
}
void su_result_free(void *raw) {
  SirinUnicodeResult *r = raw;
  if (!r) return;
  free(r->graphemes); free(r->words); free(r->lines); free(r->word_types);
  free(r->paragraphs); free(r->levels); free(r);
}
int su_analyze(void *raw, const char *text, int length, int base, void **out) {
  *out = NULL;
  if (length < 0 || length > 1048576 || base < -1 || base > 1 || !diplomat_is_str(text, (size_t)length)) return 2;
  SirinUnicode *u = raw;
  SirinUnicodeResult *r = calloc(1, sizeof(*r));
  if (!r) return 1;
  size_t cap = (size_t)length + 1;
  r->graphemes = malloc(cap * sizeof(int)); r->words = malloc(cap * sizeof(int));
  r->lines = malloc(cap * sizeof(int)); r->word_types = malloc(cap * sizeof(int));
  r->levels = malloc(cap);
  if (!r->graphemes || !r->words || !r->lines || !r->word_types || !r->levels) { su_result_free(r); return 1; }
  DiplomatStringView input = { text, (size_t)length };
  WordBreakIteratorUtf8 *w = icu4x_WordSegmenter_segment_utf8_mv1(u->words, input);
  int pos, failed = 0;
  r->counts[0] = su_unicode18_breaks((const uint8_t*)text, length, 0, r->graphemes, cap);
  r->counts[2] = su_unicode18_breaks((const uint8_t*)text, length, 2, r->lines, cap);
  if (r->counts[0] < 0 || r->counts[2] < 0) failed = 1;
  while ((pos = icu4x_WordBreakIteratorUtf8_next_mv1(w)) >= 0) {
    if (pos > length || (size_t)r->counts[1] >= cap) { failed = 1; break; }
    r->words[r->counts[1]] = pos;
    r->word_types[r->counts[1]++] = (int)icu4x_WordBreakIteratorUtf8_word_type_mv1(w);
  }
  icu4x_WordBreakIteratorUtf8_destroy_mv1(w);
  if (failed) { su_result_free(r); return 3; }
  OptionU8 level = {0}; level.is_ok = base >= 0; level.ok = base == 1 ? 1 : 0;
  BidiInfo *info = icu4x_Bidi_for_text_utf8_mv1(u->bidi, input, level);
  if (!info || icu4x_BidiInfo_size_mv1(info) != (size_t)length) {
    if (info) icu4x_BidiInfo_destroy_mv1(info);
    su_result_free(r); return 3;
  }
  r->counts[3] = length;
  size_t paragraphs = icu4x_BidiInfo_paragraph_count_mv1(info);
  if (paragraphs > cap) { icu4x_BidiInfo_destroy_mv1(info); su_result_free(r); return 3; }
  r->counts[4] = (int)paragraphs;
  r->paragraphs = malloc((paragraphs ? paragraphs : 1) * 3 * sizeof(int));
  if (!r->paragraphs) { icu4x_BidiInfo_destroy_mv1(info); su_result_free(r); return 1; }
  for (int i = 0; i < length; i++) r->levels[i] = icu4x_BidiInfo_level_at_mv1(info, (size_t)i);
  for (size_t i = 0; i < paragraphs; i++) {
    BidiParagraph *p = icu4x_BidiInfo_paragraph_at_mv1(info, i);
    r->paragraphs[i*3] = (int)icu4x_BidiParagraph_range_start_mv1(p);
    r->paragraphs[i*3+1] = (int)icu4x_BidiParagraph_range_end_mv1(p);
    r->paragraphs[i*3+2] = (int)icu4x_BidiParagraph_direction_mv1(p);
    icu4x_BidiParagraph_destroy_mv1(p);
  }
  icu4x_BidiInfo_destroy_mv1(info);
  *out = r;
  return 0;
}
void su_counts(void *raw, int *out) { memcpy(out, ((SirinUnicodeResult*)raw)->counts, 5*sizeof(int)); }
const int *su_ints(void *raw, int kind) {
  SirinUnicodeResult *r = raw;
  switch (kind) { case 0: return r->graphemes; case 1: return r->words; case 2: return r->lines;
    case 3: return r->word_types; case 4: return r->paragraphs; default: return NULL; }
}
const uint8_t *su_levels(void *raw) { return ((SirinUnicodeResult*)raw)->levels; }

typedef struct {
  int counts[3]; /* source codepoints, visual codepoints, paragraph base level */
  int *logical, *visual; /* logical triples: byte start, byte end, L1 level/-1 */
} SirinBidiLine;
extern int32_t su_bidi_line_data(const void*, size_t, size_t, size_t, size_t, int32_t*, int32_t*, int32_t*);
void su_line_free(void *raw) {
  SirinBidiLine *r = raw;
  if (!r) return;
  free(r->logical); free(r->visual); free(r);
}
int su_line(void *raw, const char *text, int length, int base, int paragraph, int start, int end, void **out) {
  *out = NULL;
  if (length < 0 || length > 1048576 || base < -1 || base > 1 || paragraph < 0 ||
      start < 0 || end < start || end > length || !diplomat_is_str(text, (size_t)length)) return 2;
  SirinUnicode *u = raw;
  OptionU8 level = {0}; level.is_ok = base >= 0; level.ok = base == 1 ? 1 : 0;
  DiplomatStringView input = {text, (size_t)length};
  BidiInfo *info = icu4x_Bidi_for_text_utf8_mv1(u->bidi, input, level);
  if (!info) return 3;
  SirinBidiLine *r = calloc(1, sizeof(*r));
  if (!r) { icu4x_BidiInfo_destroy_mv1(info); return 1; }
  size_t cap = (size_t)(end - start) + 1;
  r->logical = malloc(cap * 3 * sizeof(int)); r->visual = malloc(cap * sizeof(int));
  if (!r->logical || !r->visual) { icu4x_BidiInfo_destroy_mv1(info); su_line_free(r); return 1; }
  int status = su_bidi_line_data(info, (size_t)paragraph, (size_t)start, (size_t)end,
                                cap, r->logical, r->visual, r->counts);
  icu4x_BidiInfo_destroy_mv1(info);
  if (status) { su_line_free(r); return status; }
  *out = r;
  return 0;
}
void su_line_counts(void *raw, int *out) { memcpy(out, ((SirinBidiLine*)raw)->counts, 3*sizeof(int)); }
const int *su_line_ints(void *raw, int kind) {
  SirinBidiLine *r = raw;
  switch (kind) { case 0: return r->logical; case 1: return r->visual; default: return NULL; }
}

/* Reorder object/run levels, never UTF-8 bytes. Caller owns the output array. */
int su_order(void *raw, const uint8_t *levels, int count, int *out) {
  if (count < 0 || count > 65536) return 2;
  for (int i = 0; i < count; i++) if (levels[i] > 126) return 2;
  DiplomatU8View input = {levels, (size_t)count};
  ReorderedIndexMap *map = icu4x_Bidi_reorder_visual_mv1(((SirinUnicode*)raw)->bidi, input);
  if (!map) return 3;
  if (icu4x_ReorderedIndexMap_len_mv1(map) != (size_t)count) { icu4x_ReorderedIndexMap_destroy_mv1(map); return 3; }
  for (int i = 0; i < count; i++) out[i] = (int)icu4x_ReorderedIndexMap_get_mv1(map, (size_t)i);
  icu4x_ReorderedIndexMap_destroy_mv1(map);
  return 0;
}

#include <hb.h>
#include <hb-ot.h>
#include <ft2build.h>
#include FT_FREETYPE_H
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>

/* No structs by value, no Scala callbacks, no created threads. All borrowed
 * outputs stay valid only until the corresponding result is explicitly freed. */
typedef struct {
  unsigned char *bytes;
  FT_Library library;
  FT_Face face;
  hb_font_t *font;
} SirinFont;
typedef struct { hb_buffer_t *buffer; hb_font_t *font; } SirinRun;
typedef struct { int width, height, left, top; unsigned char *pixels; } SirinRaster;

const char *sg_text_hb_version(void) { return hb_version_string(); }
void sg_text_ft_version(void *raw, int *out) {
  FT_Library_Version(((SirinFont *)raw)->library, out, out + 1, out + 2);
}

void sg_text_close(void *raw) {
  SirinFont *f = raw;
  if (!f) return;
  if (f->font) hb_font_destroy(f->font);
  if (f->face) FT_Done_Face(f->face);
  if (f->library) FT_Done_FreeType(f->library);
  free(f->bytes);
  free(f);
}

/* Status: 1 allocation, 2 invalid font/input, 3 unsupported, 4 library failure. */
int sg_text_open(const unsigned char *bytes, int length, int face_index, int size64, void **out) {
  *out = NULL;
  if (length <= 0 || face_index < 0 || size64 <= 0) return 2;
  SirinFont *f = calloc(1, sizeof(*f));
  if (!f) return 1;
  f->bytes = malloc((size_t)length);
  if (!f->bytes) { sg_text_close(f); return 1; }
  memcpy(f->bytes, bytes, (size_t)length);
  if (FT_Init_FreeType(&f->library) || FT_New_Memory_Face(f->library, f->bytes, length, face_index, &f->face)) {
    sg_text_close(f); return 2;
  }
  if (!FT_IS_SCALABLE(f->face) || FT_HAS_COLOR(f->face) || FT_HAS_MULTIPLE_MASTERS(f->face)) {
    sg_text_close(f); return 3;
  }
  if (FT_Select_Charmap(f->face, FT_ENCODING_UNICODE)) { sg_text_close(f); return 3; }
  hb_blob_t *blob = hb_blob_create((const char *)f->bytes, (unsigned)length, HB_MEMORY_MODE_READONLY, NULL, NULL);
  hb_face_t *face = hb_face_create(blob, (unsigned)face_index);
  hb_blob_destroy(blob);
  unsigned count = hb_face_get_glyph_count(face);
  if (!count || count > INT_MAX || f->face->num_glyphs <= 0 || f->face->num_glyphs > INT_MAX ||
      count != (unsigned)f->face->num_glyphs ||
      hb_face_get_upem(face) != f->face->units_per_EM) {
    hb_face_destroy(face); sg_text_close(f); return 2;
  }
  f->font = hb_font_create(face);
  hb_face_destroy(face);
  hb_ot_font_set_funcs(f->font);
  hb_font_set_scale(f->font, size64, size64);
  *out = f;
  return 0;
}

int sg_text_metrics(void *raw, int *count, int *upem, double *metrics) {
  SirinFont *f = raw;
  hb_font_extents_t extents;
  if (!hb_font_get_h_extents(f->font, &extents)) return 4;
  *count = (int)f->face->num_glyphs;
  *upem = f->face->units_per_EM;
  metrics[0] = extents.ascender / 64.0;
  metrics[1] = -extents.descender / 64.0;
  metrics[2] = extents.line_gap / 64.0;
  return 0;
}

void sg_text_run_free(void *raw) {
  SirinRun *r = raw;
  if (r) { hb_buffer_destroy(r->buffer); hb_font_destroy(r->font); free(r); }
}
int sg_text_shape(void *raw, const char *text, int length, int rtl, const char *script,
                  const char *language, int ligatures, int start, int end,
                  int context_start, int context_end, void **out) {
  *out = NULL;
  if (length < 0 || length > 1048576 || context_start < 0 || start < context_start ||
      end < start || context_end < end || context_end > length) return 2;
  SirinFont *f = raw;
  SirinRun *r = calloc(1, sizeof(*r));
  if (!r) return 1;
  r->buffer = hb_buffer_create();
  r->font = hb_font_reference(f->font);
  hb_buffer_set_direction(r->buffer, rtl ? HB_DIRECTION_RTL : HB_DIRECTION_LTR);
  hb_buffer_set_script(r->buffer, hb_script_from_string(script, -1));
  hb_buffer_set_language(r->buffer, hb_language_from_string(language, -1));
  hb_buffer_set_cluster_level(r->buffer, HB_BUFFER_CLUSTER_LEVEL_MONOTONE_GRAPHEMES);
  /* Full paragraph context, global byte clusters; never reverse input for RTL. */
  hb_buffer_add_utf8(r->buffer, text + context_start, context_end - context_start,
                     (unsigned)(start - context_start), end - start);
  hb_buffer_set_flags(r->buffer, (hb_buffer_flags_t)((start == context_start ? HB_BUFFER_FLAG_BOT : 0) |
                                                   (end == context_end ? HB_BUFFER_FLAG_EOT : 0)));
  hb_feature_t feature = { HB_TAG('l','i','g','a'), (uint32_t)ligatures, 0, (unsigned)-1 };
  if (!hb_shape_full(r->font, r->buffer, &feature, 1, NULL) || !hb_buffer_allocation_successful(r->buffer)) {
    sg_text_run_free(r); return 4;
  }
  if (hb_buffer_get_length(r->buffer) > 1000000) { sg_text_run_free(r); return 3; }
  unsigned count;
  hb_glyph_info_t *info = hb_buffer_get_glyph_infos(r->buffer, &count);
  for (unsigned i = 0; i < count; i++) info[i].cluster += (unsigned)context_start;
  *out = r;
  return 0;
}
int sg_text_run_count(void *raw) { return (int)hb_buffer_get_length(((SirinRun *)raw)->buffer); }
/* Bounded GDEF coordinates relative to the glyph origin, in logical units.
 * Too many carets returns no usable metadata; Scala's explicit interpolation
 * policy applies. No font tables or borrowed arrays escape this call. */
int sg_text_carets(void *raw, int index, double *values, int *count) {
  SirinRun *r = raw;
  *count = 0;
  if (index < 0 || (unsigned)index >= hb_buffer_get_length(r->buffer)) return 2;
  hb_glyph_info_t *info = hb_buffer_get_glyph_infos(r->buffer, NULL);
  hb_position_t carets[64];
  unsigned n = 64;
  unsigned total = hb_ot_layout_get_ligature_carets(r->font, hb_buffer_get_direction(r->buffer),
      info[index].codepoint, 0, &n, carets);
  if (total > 64) return 0;
  for (unsigned i = 0; i < n; i++) values[i] = carets[i] / 64.0;
  *count = (int)n;
  return 0;
}
int sg_text_glyph(void *raw, int index, uint32_t *glyph, uint32_t *cluster, double *values, int *has_ink) {
  SirinRun *r = raw;
  unsigned count = hb_buffer_get_length(r->buffer);
  if (index < 0 || (unsigned)index >= count) return 2;
  hb_glyph_info_t *info = hb_buffer_get_glyph_infos(r->buffer, NULL);
  hb_glyph_position_t *pos = hb_buffer_get_glyph_positions(r->buffer, NULL);
  *glyph = info[index].codepoint;
  *cluster = info[index].cluster;
  values[0] = pos[index].x_advance / 64.0;
  values[1] = -pos[index].y_advance / 64.0;
  values[2] = pos[index].x_offset / 64.0;
  values[3] = -pos[index].y_offset / 64.0;
  hb_glyph_extents_t ink;
  *has_ink = hb_font_get_glyph_extents(r->font, *glyph, &ink) && (ink.width || ink.height);
  if (*has_ink) {
    double x0 = ink.x_bearing / 64.0, x1 = (ink.x_bearing + (double)ink.width) / 64.0;
    double y0 = -ink.y_bearing / 64.0, y1 = -(ink.y_bearing + (double)ink.height) / 64.0;
    values[4] = x0 < x1 ? x0 : x1; values[5] = y0 < y1 ? y0 : y1;
    values[6] = x0 < x1 ? x1-x0 : x0-x1; values[7] = y0 < y1 ? y1-y0 : y0-y1;
  }
  return 0;
}

void sg_text_raster_free(void *raw) {
  SirinRaster *r = raw;
  if (r) { free(r->pixels); free(r); }
}
int sg_text_raster(void *raw, unsigned glyph, int size64, int phase64, int max_pixels, void **out) {
  *out = NULL;
  SirinFont *f = raw;
  if (glyph >= (unsigned)f->face->num_glyphs || size64 <= 0 || size64 > 4096*64 ||
      phase64 < 0 || phase64 >= 64 || max_pixels <= 0) return 2;
  if (FT_Set_Char_Size(f->face, 0, size64, 72, 72)) return 4;
  FT_Vector delta = { phase64, 0 };
  FT_Set_Transform(f->face, NULL, &delta);
  if (FT_Load_Glyph(f->face, glyph, FT_LOAD_NO_HINTING | FT_LOAD_NO_BITMAP | FT_LOAD_NO_AUTOHINT)) return 4;
  FT_GlyphSlot slot = f->face->glyph;
  double bound_w = slot->metrics.width / 64.0 + 4, bound_h = slot->metrics.height / 64.0 + 4;
  if (bound_w * bound_h > max_pixels) return 3;
  if (FT_Render_Glyph(slot, FT_RENDER_MODE_NORMAL)) return 4;
  FT_Bitmap *b = &slot->bitmap;
  if ((b->width && b->rows) && (b->pixel_mode != FT_PIXEL_MODE_GRAY || b->num_grays != 256 || b->pitch < (int)b->width)) return 3;
  size_t length = (size_t)b->width * b->rows;
  if (length > (size_t)max_pixels || b->width > INT_MAX || b->rows > INT_MAX) return 3;
  SirinRaster *r = calloc(1, sizeof(*r));
  if (!r) return 1;
  if (length) {
    r->pixels = malloc(length);
    if (!r->pixels) { free(r); return 1; }
    for (unsigned y = 0; y < b->rows; y++)
      memcpy(r->pixels + (size_t)y*b->width, b->buffer + (size_t)y*b->pitch, b->width);
  }
  r->width = (int)b->width; r->height = (int)b->rows;
  r->left = slot->bitmap_left; r->top = slot->bitmap_top;
  *out = r;
  return 0;
}
void sg_text_raster_meta(void *raw, int *out) {
  SirinRaster *r = raw;
  out[0] = r->width; out[1] = r->height; out[2] = r->left; out[3] = r->top;
}
const unsigned char *sg_text_raster_pixels(void *raw) { return ((SirinRaster *)raw)->pixels; }

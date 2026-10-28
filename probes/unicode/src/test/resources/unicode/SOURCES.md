# Unicode reference fixtures

Unmodified Unicode 18.0.0 reference data, downloaded 2026-10-27:

- https://www.unicode.org/Public/18.0.0/ucd/auxiliary/GraphemeBreakTest.txt
- https://www.unicode.org/Public/18.0.0/ucd/auxiliary/WordBreakTest.txt
- https://www.unicode.org/Public/18.0.0/ucd/auxiliary/LineBreakTest.txt
- https://www.unicode.org/Public/18.0.0/ucd/BidiCharacterTest.txt
- https://www.unicode.org/license.txt (saved as LICENSE.txt)

Copyright and license headers remain in the files. SHA256SUMS records the exact
downloaded bytes, verified by `probeUnicode/test` before execution. Unicode
18.0.0 matches the pinned ICU4X 2.4.0 release data; hashes are local download
receipts, not independent signature verification.

Segmentation tests use every non-comment record and compare exact UTF-8 boundary
vectors. For line breaking only, the structural start offset zero is added to
the expected vector because the ICU iterator includes it while UAX #14 LB2 marks
it as not a wrapping opportunity. This does not change any internal/end breaks.
The original raw-bidi test uses the first 16 records. `BidiLineSuite` separately
uses every one of the 91,707 BidiCharacterTest records, comparing paragraph base
levels, line-dependent levels (including `x` for X9) and the exact L2 visual
codepoint indices. That corpus explicitly tests through L2 only. L3/L4, glyph
layout and the separate BidiTest class-sequence corpus are not certified.

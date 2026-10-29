// This static library belongs only to the isolated P0.8 process. It is never
// co-linked with wgpu-native or AccessKit; host integration needs sirin-native.
pub use icu_capi::*;

// Bounded, allocation-free table lookups. Scalar tags are four ASCII bytes;
// no Rust or Diplomat structures cross the C ABI. Outputs have 5/64 i32 slots.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn su_script_info(cp: u32, out: *mut i32, tags: *mut i32) -> i32 {
    use icu_properties::props::{
        BidiMirroringGlyph, BidiPairedBracketType, DefaultIgnorableCodePoint, Script,
        VariationSelector,
    };
    use icu_properties::script::ScriptWithExtensions;
    use icu_properties::{CodePointMapData, CodePointSetData, PropertyNamesShort};
    if out.is_null() || tags.is_null() {
        return 2;
    }
    let Some(ch) = char::from_u32(cp) else {
        return 2;
    };
    let names = PropertyNamesShort::<Script>::new();
    let pack = |s| {
        names
            .get(s)
            .filter(|n| n.len() == 4)
            .map(|n| i32::from_be_bytes(n.as_bytes().try_into().unwrap()))
    };
    let data = ScriptWithExtensions::new();
    let Some(primary) = pack(data.get_script_val(ch)) else {
        return 3;
    };
    let mut count = 0;
    for script in data.get_script_extensions_val(ch).iter() {
        if count == 64 {
            return 3;
        }
        let Some(tag) = pack(script) else {
            return 3;
        };
        unsafe {
            tags.add(count).write(tag);
        }
        count += 1;
    }
    let bidi = CodePointMapData::<BidiMirroringGlyph>::new().get(ch);
    let kind = match bidi.paired_bracket_type {
        BidiPairedBracketType::Open => 1,
        BidiPairedBracketType::Close => 2,
        _ => 0,
    };
    let flags = i32::from(CodePointSetData::new::<DefaultIgnorableCodePoint>().contains(ch))
        | (i32::from(CodePointSetData::new::<VariationSelector>().contains(ch)) << 1);
    unsafe {
        out.write(primary);
        out.add(1).write(count as i32);
        out.add(2).write(kind);
        out.add(3)
            .write(bidi.mirroring_glyph.map(|c| c as i32).unwrap_or(-1));
        out.add(4).write(flags);
    }
    0
}

// The generated API returns a reordered string but not the L1 levels/source
// map needed for shaping and editing. Access the exact upstream BidiInfo that
// it created, without mirroring its layout in C or Scala.
// Safety: info must be live from the matching ICU4X C API. The C shim supplies
// writable arrays of 3*capacity and capacity i32s, and three count/base slots.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn su_bidi_line_data(
    info: *const icu_capi::unstable::bidi::ffi::BidiInfo<'_>,
    paragraph: usize,
    start: usize,
    end: usize,
    capacity: usize,
    logical: *mut i32,
    visual: *mut i32,
    counts: *mut i32,
) -> i32 {
    if info.is_null() || logical.is_null() || visual.is_null() || counts.is_null() {
        return 2;
    }
    let info = &unsafe { &*info }.0;
    let Some(para) = info.paragraphs.get(paragraph) else {
        return 2;
    };
    if start > end
        || start < para.range.start
        || end > para.range.end
        || end - start > 1_048_576
        || capacity != end - start + 1
        || !info.text.is_char_boundary(start)
        || !info.text.is_char_boundary(end)
    {
        return 2;
    }
    let levels = info.reordered_levels(para, start..end);
    let mut retained = Vec::new();
    let mut retained_levels = Vec::new();
    let mut count = 0;
    for (local, ch) in info.text[start..end].char_indices() {
        let offset = start + local;
        use unicode_bidi::BidiClass::{BN, LRE, LRO, PDF, RLE, RLO};
        let removed = matches!(
            info.original_classes[offset],
            BN | LRE | RLE | LRO | RLO | PDF
        );
        unsafe {
            logical.add(count * 3).write(offset as i32);
            logical
                .add(count * 3 + 1)
                .write((offset + ch.len_utf8()) as i32);
            logical.add(count * 3 + 2).write(if removed {
                -1
            } else {
                levels[offset].number() as i32
            });
        }
        if !removed {
            retained.push(offset);
            retained_levels.push(levels[offset]);
        }
        count += 1;
    }
    let order = unicode_bidi::BidiInfo::reorder_visual(&retained_levels);
    for (i, logical_index) in order.iter().enumerate() {
        unsafe {
            visual.add(i).write(retained[*logical_index] as i32);
        }
    }
    unsafe {
        counts.write(count as i32);
        counts.add(1).write(order.len() as i32);
        counts.add(2).write(para.level.number() as i32);
    }
    0
}

// ICU4X 2.4's generated C API selects older grapheme/line algorithms. Use the
// pinned Unicode 18 constructors behind a bounded pointer-only C ABI. No custom
// Unicode rules, retained input, Rust objects or unwinding cross this boundary.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn su_unicode18_breaks(
    text: *const u8,
    length: usize,
    kind: i32,
    out: *mut i32,
    capacity: usize,
) -> i32 {
    if text.is_null() || out.is_null() || length > 1_048_576 || capacity != length + 1 {
        return -1;
    }
    let bytes = unsafe { core::slice::from_raw_parts(text, length) };
    let Ok(input) = core::str::from_utf8(bytes) else {
        return -1;
    };
    fn copy(iter: impl Iterator<Item = usize>, out: *mut i32, capacity: usize) -> i32 {
        let mut count = 0;
        for offset in iter {
            if count >= capacity || offset > i32::MAX as usize {
                return -1;
            }
            unsafe {
                out.add(count).write(offset as i32);
            }
            count += 1;
        }
        count as i32
    }
    match kind {
        0 => copy(
            icu_segmenter::GraphemeClusterSegmenter::new_neo().segment_str(input),
            out,
            capacity,
        ),
        2 => {
            let options = icu_segmenter::options::LineBreakOptions::default();
            copy(
                icu_segmenter::LineSegmenter::new_neo_for_non_complex_scripts(options)
                    .segment_str(input),
                out,
                capacity,
            )
        }
        _ => -1,
    }
}

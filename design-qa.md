# Search keyboard — option 2

## Comparison target

- Selected visual: `$HOME/.codex/generated_images/01a17eab-2be2-7b23-8009-20644b0c5393/exec-5929b4ab-7152-4964-8aed-2d502cba3f6d.png`.
- Implementation: native Java/XML Search UI in `android/app`, on the verified `Stream_Local_TV` emulator (`emulator-5558`, API 36).
- Source pixels: 1672 × 941. Emulator pixels: 1920 × 1080, a 960 × 540 dp canvas at density 2. CSS size is not applicable to this native app.
- Compare the empty Search screen with `q` focused, navigation hidden, and the keyboard visible. The device clock advances independently of the mock.
- Full-view evidence: `simulator/.cache/evidence/keyboard-option2-comparison-before.png` contains both source and emulator capture. Each is fitted proportionally into 960 × 540 pixels without changing the UI itself.
- Final implementation screenshot: `simulator/.cache/evidence/search-popup-keyboard.png`.
- Final full-view comparison: `simulator/.cache/evidence/keyboard-option2-comparison.png`.
- Focused comparison: `simulator/.cache/evidence/keyboard-option2-detail.png` shows the source keyboard crop and the final native keyboard crop together, proportionally fitted into 520 × 640 pixel regions. It makes letters, focus, actions, icons and the divider readable without claiming identical physical component sizes.

## Fidelity surfaces

- **Typography:** keep bundled Geist and the existing 16 sp letters / 12 sp action captions. Native system fallback covers Cyrillic; exact existing accessibility labels remain intact.
- **Spacing:** retain the existing compact 224 dp key grid and results-column position. Add one 22 dp-radius popup surface, internal padding, a separator above the page actions, and capsule actions. This adapts the generated mock's larger keyboard to the established native layout. Query and keys remain anchored at the top across language changes.
- **Padding refinement:** the latest user reference is `/var/folders/2t/p7wv0nhn1zl5qfwk2l9lhx7r0000gn/T/codex-clipboard-f9eeeed6-ac0a-408c-8f8a-237660a54db1.png`. Surface padding now uses a single 6 dp inset on all four sides (12 px at the emulator's density). Measured content-to-panel bounds match on both axes for all 24 language/character layouts.
- **Colors:** reuse the opaque `tv_material_dialog` surface, white focus and near-black focused text, quiet platter tints and the app's hairline. The original page canvas and secondary empty-query color are outside this keys-only redesign and stay in use.
- **Assets:** existing native Geist globe/backspace vectors stay sharp; all text remains live. The keyboard introduces no raster UI or generated background texture.
- **Copy:** retain Search, Space, Clear, language names and character-page labels, plus the existing results prompt and actions. No new product features or visible implementation text.

## Findings and comparison history

1. Initial verification found the query-to-key gap exceeded its existing 24 dp limit after panel padding was added. Reduced the external gap from 14 to 12 dp; the existing Search regression then passed.
2. **[P2, repaired] Cramped one-cell Space action on short accent pages.** The first German capture (`simulator/.cache/evidence/keyboard-popup-short-before.png`) showed Space squeezed into a 34 dp circle. Four/five trailing characters now spread evenly across their row; Space and Delete receive their own full row when fewer than three cells remain. This preserves a two-cell minimum for the Space caption, fixed top anchors, and filled row edges. The revised capture (`simulator/.cache/evidence/keyboard-popup-short.png`) and same-scale before/after comparison (`simulator/.cache/evidence/keyboard-short-layout-fix.png`) show a readable Space capsule and a round focus circle in the wider letter hit cell.
3. Final full-view and focused comparisons find no remaining actionable P0/P1/P2 differences within the keyboard redesign. The original native grid width, underlying page canvas, secondary empty-query color and advancing clock are intentional adaptations, rather than parts of the keyboard style to replace with generated pixels. The popup surface, bare letters, white circular focus, capsule actions, glyphs and separator match the selected direction.
4. **[P2, repaired] Unequal padding and abrupt motion.** Replaced 6/12/6/10 dp padding with equal 6 dp insets. Replaced the 120 ms hide followed by a separate reveal with continuous 360 ms translation/opacity easing in both directions. The grid fades out for its span change while the keyboard continues moving, then fades back in. Gesture reversal cancels the previous direction without resetting position or alpha; a reversal that needs no column change also avoids another outgoing grid fade. Updated screenshot: `simulator/.cache/evidence/search-popup-keyboard.png`; inspected motion evidence: `simulator/.cache/evidence/keyboard-motion-preview.mp4`, with its 8 fps contact sheet at `simulator/.cache/evidence/keyboard-motion-preview-contact.png`.
5. The pending full-screen results-viewport extension had a layout feedback loop before a transition could start. Its bottom extension is now measured from fixed parent bounds, preserving that local viewport work while allowing layout to settle. Focus/selection recovery and returning to the exact saved viewport pass again.
6. **[P2, repaired] Missing control growth.** Keyboard controls had explicitly disabled scaling. Letters and single-cell icon controls now use the shared 12% icon lift; wider action capsules use the shared 5% button lift. Both focus and pointer hover use the existing 200/160 ms easing. The popup allows drawing beyond child/padding clips, while row bounds, proportions and spacing remain fixed. Pointer hover preserves the remote's focused key. Inspected evidence: `simulator/.cache/evidence/keyboard-hover-lift.png` shows a grown focused letter and a grown hovered full-width language capsule without clipping.
7. **[P2, repaired] Oversized language-key growth.** The fixed 5% capsule scale added 11.2 dp to the language key's width, exceeding the letter circle's 4.08 dp growth. Scale now depends on the visible key width: letters keep 12%, two-cell actions use about 5.7%, and the full-width language key uses about 1.8%. All add the same visible width, using uniform scaling and the existing easing. Wider letter hit cells still retain their 34 dp circular platter and its original growth. Inspected `simulator/.cache/evidence/keyboard-width-focus-lift.png` and `simulator/.cache/evidence/keyboard-width-hover-lift.png`: the language platter stays inside the popup inset, with no clipping or displaced rows.

## Verification

- Initial style pass: seven of eight focused tests passed; the query spacing regression identified above was repaired.
- Second pass: `OK (8 tests)`, including all eight languages × three character pages, remote/touch/mouse navigation, typing and clearing, grid transitions, selected-film restoration, activity recreation, and safe-area bounds.
- Final pass after the short-caption repair: `OK (8 tests)` in 55.317 seconds. The retained runner output is `simulator/.cache/evidence/tv.stream.app.test-instrumentation.txt`.
- Additional final captures cover the shortest German accent layout and the longer Russian alphabet (`simulator/.cache/evidence/keyboard-popup-long.png`); both keep all actions above the bottom safe area.
- Native instrumentation is used instead of browser/console checks; no website or web prototype was created.
- Latest padding/motion pass: `OK (8 tests)` in 61.272 seconds. Retained output: `simulator/.cache/evidence/keyboard-padding-motion-tests.txt`. It includes simultaneous keyboard/grid fading, an eased movement tail, no visible per-card crossings, reversal position/opacity continuity, all 24 padding/anchor layouts, activity recreation, and nested viewport recovery.
- A separate recorded motion run passed `OK (1 test)` in 5.7 seconds. Only the generated local fixture screen is included in the trimmed preview; animation timing is unchanged.
- Control-growth verification: the 24-layout/padding test and Search navigation test passed; the focused keyboard geometry/input test passed `OK (1 test)` in 8.884 seconds after its pointer precondition was made explicit. It checks remote growth/reset, unchanged row geometry, wide capsule growth, mouse enter/exit, and preservation of remote focus during hover. No production behavior changed after these checks.
- Size-aware growth verification: `OK (2 tests)` in 32.552 seconds, retained in `simulator/.cache/evidence/keyboard-width-lift-tests.txt`. The input regression compares completed visible growth across letter, medium and full-width keys for focus and hover, checks smaller scales for wider keys, and preserves row geometry and remote focus. The second test covers all 24 language/character layouts and their padding/top anchors.

## Implementation checklist

- [x] Shared popup surface, bare letters and circular focus.
- [x] Capsule actions, separator and pointer hover.
- [x] Hidden Search navigation and fixed top anchors.
- [x] Recapture and compare the repaired short accent layout.
- [x] Confirm the final focused instrumentation run passes.

## Remaining limits

- Verification covers the local Android TV 16 emulator; it does not claim physical-TV or other-platform validation.
- The generated concept's decorative material variations are represented by the app's existing flat popup token. No extra texture, background image or runtime asset request was added.

final result: passed

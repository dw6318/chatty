# Additional artwork and data notices

This supplements the original [README licence inventory](README.md#license-information).
The Chatty application is GPL-3.0-or-later. These artwork/data files retain their
own licences; bundling them does not relicense them as GPL. Full texts are stored
with each asset set and included in the application JAR.

| Component | Source/version | Licence and changes |
| --- | --- | --- |
| Twemoji supplement | [jdecked/twemoji v17.0.3](https://github.com/jdecked/twemoji/tree/v17.0.3) | Twitter, Inc. and contributors; CC-BY-4.0. Selected PNG artwork unchanged. |
| Unicode sequence/name data and test fixture | [Unicode Emoji 17.0](https://unicode.org/Public/17.0.0/emoji/emoji-test.txt) | Unicode, Inc.; Unicode License v3. Catalog reformatted/filtered; original test-data header retained. |
| Fluent Emoji 3D | [Microsoft, commit 1ffb34c7](https://github.com/microsoft/fluentui-emoji/tree/1ffb34c752ecf5d402f04cfb4b392c77f57c54bc) | Copyright Microsoft Corporation; MIT. PNGs resized to fit 72x72 and filenames mapped to Unicode. |
| Noto Emoji 2D | [Google, commit 06121655](https://github.com/googlefonts/noto-emoji/tree/06121655d0e82f9cae6e7ba6feed4fa6fdbfc2a4) | Copyright Google; Apache-2.0 image resources, with separate flag provenance. PNGs resized to fit 72x72. No fonts included. |
| OpenMoji color artwork | [OpenMoji 17.0.0](https://github.com/hfg-gmuend/openmoji/releases/tag/17.0.0) | HfG Schwaebisch Gmuend, Benedikt Gross, Daniel Utz and OpenMoji contributors; CC-BY-SA-4.0. Selected original 72x72 PNG bytes unchanged; filenames normalized. |

All OpenMoji emoji designed by [OpenMoji](https://openmoji.org/) - the open-source
emoji and icon project. Licence: [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
Keep attribution and licence links when redistributing; adaptations of that
artwork must comply with ShareAlike. No endorsement by any provider is implied.

Asset paths are under `src/chatty/gui/emoji/{twemoji-modern,fluent,noto,openmoji}`.
The new style folders contain `NOTICE.txt`, full licence texts and a per-image
`ASSET-MANIFEST.tsv` with source paths and original/bundled SHA-256 hashes.
Unicode licence text is in `twemoji-modern/UNICODE-LICENSE.txt`.
The Noto flag notices are `noto/FLAGS-LICENSE.txt` and `noto/FLAGS-README.md`.

The new styles use the existing Twemoji/Unicode metadata rather than importing
vendor shortcode databases. Fluent Unicode IDs are read from MIT-licensed
upstream metadata. The import script itself follows this project's GPL licence.

Dependency licence texts and copyright headers are preserved separately under
`src/chatty/licenses/dependencies` (or `chatty/licenses/dependencies` in the JAR).
Its README records their source archives. Keeping them under component-specific
names avoids losing notices when dependencies share `META-INF/NOTICE` paths.

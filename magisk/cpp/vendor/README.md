# HC embedded dependencies

Only upstream source is vendored. Exact repositories/commits are in `sources.json`.
LSPlant v6.4 and its pinned DexBuilder/parallel-hashmap revisions provide ART method
hooking; Dobby's Android-build-fix revision provides inline hook/unhook. Dobby master
failed the pinned r27d build and is not used. No network fetch is performed by CI or
the native build. Upstream licenses are retained and packaged with the core.

The existing xDL resolver outside this directory supplies exact/prefix ART symbol
resolution. The local prefix extension is in `native/gps-copy/cpp/xdl/xdl.c`, not an
untracked upstream patch. Dobby's date-based build identifier is fixed through
`SOURCE_DATE_EPOCH` in the build script. These libraries do not constitute a separate
Android app, LSPosed installation, Health Connect provider, or permission grant.

This is private development; no new public distribution has been authorized.

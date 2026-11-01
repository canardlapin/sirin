# Independent consumer and local SDK

The starter is a separate sbt project copied outside the framework checkout.
It consumes an exported binary SDK, not `ProjectRef`, source directories or
framework-internal imports. The export derives from the existing macOS/JDK25
development image, preserving its provider service, native dependency closure
and bundled fonts. A complete file/link manifest binds the consumer package to
that export. This is a local development workflow, not a publication or platform
delivery-order decision. A new artifact repository and dependency release were
set aside because neither is qualified yet.

ReviewPad is the independent feature: persistent title, notes and review status,
with validation, asynchronous snapshot saves, error recovery and guarded quit.
The storage adapter belongs to the consumer and uses public `Work`; the UI uses
public application/control APIs. A repository-internal example would not prove
this dependency boundary. The copied consumer must build and package without
framework source paths, then receive its own real-window acceptance evidence.

The starter does not replace the full workbench, inspector, matched ScalaFX
comparison or Native provider. Those remain separately tracked. No kernel or
command-policy changes are introduced.

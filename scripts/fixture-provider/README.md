# Forge Fixtures (local SAF test APK)

Run `./build.sh` here to produce `build/forge-fixture-provider-debug.apk`.
This package is independent of FORGE AI and does not need shared storage.
It contains no activities or services. Its only exported component is the
`MANAGE_DOCUMENTS`-protected provider. SAF grants access to selected files.

On the intended test emulator:

```sh
adb install -t -r build/forge-fixture-provider-debug.apk
adb logcat -s ForgeFixtures:I
```

In FORGE AI, select **Import GGUF → Show roots → Forge Fixtures**. Select
`forge-invalid.gguf` to test rejection, or `forge-qwen-fixture.gguf` to test
import (a tiny synthetic GGUF, not an inference model). Logcat records root
queries, directory listings and file opens. Remove the provider after testing
with `adb uninstall dev.forge.fixtureprovider`.

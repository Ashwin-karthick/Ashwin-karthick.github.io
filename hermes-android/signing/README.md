# Test-only signing key

`hermes-mobile-test.jks` signs every test build so a new APK can update the previous one without
uninstalling (an uninstall would delete the installed Linux and Hermes).

It is public on purpose and protects nothing. Anyone can sign an APK with it. Do not install builds
signed with it from anyone else, and create a private key before any public release.

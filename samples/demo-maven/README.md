# demo-maven

A Maven reactor with yoriwake declared once in the root POM: `lib`, `app` (depends on `lib`), a
Kotlin module with an `inline` function, and a module on the module path (`module-info.java`).

```bash
mvn verify
```

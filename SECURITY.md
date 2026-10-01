# Security policy

Report a vulnerability privately through GitHub's private vulnerability reporting for
`zeuspizza/yoriwake`: the repository's **Security** tab, then **Report a vulnerability**. Please do
not open a public issue. No response time is promised. The agent runs as a `-javaagent` inside
your test JVM and the plugin runs inside your Gradle build, so issues in either are in scope, as
is anything in how the coverage map is read back.

package io.github.zeuspizza.yoriwake.maven

// Decisions about a plugin's configuration that need nothing of Maven, so a unit test can load
// them without Maven on its class path.

private val EXPRESSION = Regex("""[$@]\{([^}]+)}""")

/**
 * [value] with each `${name}` and `@{name}` replaced by what [lookup] knows of the name, nothing
 * where it knows none; null when nothing but blanks is left.
 */
internal fun resolveExpressions(value: String?, lookup: (String) -> String?): String? =
    value?.let { EXPRESSION.replace(it) { m -> lookup(m.groupValues[1]).orEmpty() } }?.takeIf(String::isNotBlank)

/**
 * Whether Surefire or Failsafe at [version] hands the fork the invocation's user properties:
 * always before 3.4.0, and from then unless `promoteUserPropertiesToSystemProperties`, [value]
 * once resolved, is false. A version that cannot be read counts as before 3.4.0, so the
 * properties are compared rather than left out.
 */
internal fun promotesUserProperties(value: String?, version: String?): Boolean {
    if (!value?.trim().equals("false", ignoreCase = true)) return true
    val parts = version.orEmpty().split('.', '-').map { it.toIntOrNull() ?: 0 }
    val major = parts.getOrElse(0) { 0 }
    return major < 3 || (major == 3 && parts.getOrElse(1) { 0 } < 4)
}

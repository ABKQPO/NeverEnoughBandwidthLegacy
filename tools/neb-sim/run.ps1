param(
    [string]$GradleUserHome = "",
    [string]$Dataset = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$out = Join-Path $root "build\neb-sim"
New-Item -ItemType Directory -Force -Path $out | Out-Null

if (-not $GradleUserHome) {
    if ($env:GRADLE_USER_HOME) {
        $GradleUserHome = $env:GRADLE_USER_HOME
    } elseif (Test-Path "F:\.gradle") {
        $GradleUserHome = "F:\.gradle"
    } else {
        $GradleUserHome = Join-Path $env:USERPROFILE ".gradle"
    }
}

$modules = Join-Path $GradleUserHome "caches\modules-2\files-2.1"
if (-not (Test-Path $modules)) {
    throw "Cannot find the Gradle module cache under $modules"
}

function Find-Jar([string]$group, [string]$module, [string]$version) {
    $path = Join-Path $modules "$group\$module\$version"
    if (-not (Test-Path $path)) {
        throw "Missing $group`:$module`:$version in the Gradle cache; run .\gradlew.bat compileJava first"
    }
    $jar = Get-ChildItem $path -Recurse -Filter "$module-$version.jar" |
        Select-Object -First 1
    if (-not $jar) {
        throw "Missing $module-$version.jar in $path"
    }
    return $jar.FullName
}

$netty = "4.1.109.Final"
$jars = @(
    (Find-Jar "com.github.luben" "zstd-jni" "1.5.7-5"),
    (Find-Jar "io.netty" "netty-buffer" $netty),
    (Find-Jar "io.netty" "netty-codec" $netty),
    (Find-Jar "io.netty" "netty-common" $netty),
    (Find-Jar "io.netty" "netty-handler" $netty),
    (Find-Jar "io.netty" "netty-transport" $netty),
    (Find-Jar "io.netty" "netty-resolver" $netty)
)

$log4j = Get-ChildItem (Join-Path $modules "org.apache.logging.log4j\log4j-api") -Recurse -Filter "log4j-api-*.jar" |
    Sort-Object Name -Descending |
    Select-Object -First 1
if ($log4j) {
    $jars += $log4j.FullName
}

$classes = Join-Path $root "build\classes\java\main"
$tags = Join-Path $root "build\classes\java\injectedTags"
if (-not (Test-Path (Join-Path $classes "cn\ussshenzhou\notenoughbandwidth\codec\PacketCodecRegistry.class"))) {
    throw "Mod classes are missing; run .\gradlew.bat compileJava first"
}

$source = Join-Path $PSScriptRoot "simsrc\NebPacketSimulation.java"
$classpath = (@($classes, $tags) + $jars) -join ";"
$compiled = Join-Path $out "classes"
New-Item -ItemType Directory -Force -Path $compiled | Out-Null

& javac -encoding UTF-8 -nowarn -d $compiled -cp $classpath $source
if ($LASTEXITCODE -ne 0) {
    throw "javac failed"
}

& java -Xmx3G -cp (@($compiled, $classpath) -join ";") NebPacketSimulation $Dataset
exit $LASTEXITCODE

param(
    [string]$JdkPath = $env:JAVA_HOME
)

# Use a separate output directory for every build and retain all previous runs.
# This launcher intentionally exposes no clean or Windows installer tasks.
Set-StrictMode -Version Latest
if ([string]::IsNullOrWhiteSpace($JdkPath)) { throw 'Set JAVA_HOME to JDK 17 or pass -JdkPath.' }

$taskJava = Join-Path $JdkPath 'bin\java.exe'
$taskJavac = Join-Path $JdkPath 'bin\javac.exe'
if (!(Test-Path -LiteralPath $taskJava) -or !(Test-Path -LiteralPath $taskJavac)) {
    throw "A full JDK is required at $JdkPath. Supply -JdkPath to use another JDK 17 installation."
}

$taskRunName = (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$taskRunRoot = Join-Path (Split-Path $PSScriptRoot -Parent) ('dw6318-builds\' + $taskRunName)
New-Item -ItemType Directory -Path $taskRunRoot -ErrorAction Stop | Out-Null
$taskOutput = Join-Path $taskRunRoot 'output'
$taskInitFile = Join-Path $taskRunRoot 'dw6318-build.init.gradle'
$taskLog = Join-Path $taskRunRoot 'build.log'

$taskInit = @'
gradle.beforeProject { project ->
    project.layout.buildDirectory.set(new File(
        gradle.startParameter.projectProperties.get('chattyForkBuildDir')))
}
'@
[System.IO.File]::WriteAllText($taskInitFile, $taskInit, [System.Text.UTF8Encoding]::new($false))

Write-Output "JDK: $JdkPath"
Write-Output "Build directory: $taskRunRoot"

$taskWrapper = Join-Path $PSScriptRoot 'gradle\wrapper\gradle-wrapper.jar'
$taskArguments = @(
    '-Xmx64m', '-Xms64m',
    "-Dorg.gradle.java.home=$JdkPath",
    '-Dorg.gradle.appname=gradlew',
    '-classpath', $taskWrapper,
    'org.gradle.wrapper.GradleWrapperMain',
    'build', 'shadowJar', '--no-daemon', '--console=plain',
    '--project-dir', $PSScriptRoot,
    '--project-cache-dir', (Join-Path $taskRunRoot 'project-cache'),
    '--init-script', $taskInitFile,
    "-PchattyForkBuildDir=$taskOutput"
)

& $taskJava @taskArguments 2>&1 | ForEach-Object { $_.ToString() } | Tee-Object -FilePath $taskLog
$taskBuildExitCode = $LASTEXITCODE
Write-Output "Build log retained at: $taskLog"
Write-Output "Artifacts and test reports: $taskOutput"
if ($taskBuildExitCode -ne 0) {
    throw "Gradle build failed with exit code $taskBuildExitCode. See $taskLog."
}

# Retain Gradle's standard artifact and also provide the named fork build.
$taskForkJar = Join-Path $taskOutput 'libs\Chatty-dw6318.jar'
Copy-Item -LiteralPath (Join-Path $taskOutput 'libs\Chatty.jar') -Destination $taskForkJar -ErrorAction Stop
Write-Output "dw6318 build: $taskForkJar"

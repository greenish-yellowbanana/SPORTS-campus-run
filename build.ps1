param(
    [Parameter(Mandatory=$true)][string]$JdkDir,
    [Parameter(Mandatory=$true)][string]$BuildToolsDir,
    [Parameter(Mandatory=$true)][string]$AndroidJar,
    [Parameter(Mandatory=$true)][string]$XposedApi,
    [string]$OutputDir = (Join-Path $PSScriptRoot 'dist'),
    [string]$EcjJar = ''
)
$ErrorActionPreference='Stop'
$JdkDir=(Resolve-Path $JdkDir).Path
$BuildToolsDir=(Resolve-Path $BuildToolsDir).Path
$AndroidJar=(Resolve-Path $AndroidJar).Path
$XposedApi=(Resolve-Path $XposedApi).Path
New-Item -ItemType Directory -Force $OutputDir | Out-Null
$OutputDir=(Resolve-Path $OutputDir).Path
function Invoke-Native([string]$File,[string[]]$Arguments) {
    & $File @Arguments
    if($LASTEXITCODE -ne 0){throw "Build tool exited $LASTEXITCODE : $File"}
}
$java=Join-Path $JdkDir 'bin/java.exe'
$javac=Join-Path $JdkDir 'bin/javac.exe'
$keytool=Join-Path $JdkDir 'bin/keytool.exe'
$keystore=Join-Path $PSScriptRoot 'local-test-signing.jks'
if(-not (Test-Path -LiteralPath $keystore)){
    Invoke-Native -File $keytool -Arguments @('-genkeypair','-keystore',$keystore,'-alias','localtest','-storepass','android','-keypass','android','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=VirtualSteps Local Test')
}
foreach($part in @('module','receiver')){
    $source=Join-Path $PSScriptRoot $part
    $stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $build=Join-Path $PSScriptRoot "build/$part-$stamp"
    $classes=Join-Path $build 'classes'
    $dex=Join-Path $build 'dex'
    New-Item -ItemType Directory -Force $classes,$dex | Out-Null
    $base=Join-Path $build 'base.apk'
    $aaptArguments=@('link','-I',$AndroidJar,'--manifest',(Join-Path $source 'AndroidManifest.xml'),'--min-sdk-version','27','--target-sdk-version','35','-o',$base)
    if(Test-Path (Join-Path $source 'assets')){ $aaptArguments+=@('-A',(Join-Path $source 'assets')) }
    Invoke-Native -File (Join-Path $BuildToolsDir 'aapt2.exe') -Arguments $aaptArguments
    $files=@(Get-ChildItem (Join-Path $source 'src') -Filter '*.java' -Recurse | ForEach-Object FullName)
    $classpath=$AndroidJar
    if($part -eq 'module'){$classpath+=";$XposedApi"}
    $bootClasspath=$AndroidJar
    $lambdaStubs=Join-Path $BuildToolsDir 'core-lambda-stubs.jar'
    if(Test-Path $lambdaStubs){$bootClasspath+=";$lambdaStubs"}
    $compileArguments=@('-encoding','UTF-8','-source','8','-target','8','-bootclasspath',$bootClasspath,'-classpath',$classpath,'-d',$classes)+$files
    if($EcjJar){
        Invoke-Native -File $java -Arguments (@('-jar',(Resolve-Path $EcjJar).Path)+$compileArguments)
    }else{
        Invoke-Native -File $javac -Arguments $compileArguments
    }
    $compiled=@(Get-ChildItem $classes -Filter '*.class' -Recurse | ForEach-Object FullName)
    $d8Arguments=@('-cp',(Join-Path $BuildToolsDir 'lib/d8.jar'),'com.android.tools.r8.D8','--min-api','27','--lib',$AndroidJar,'--output',$dex)
    if($part -eq 'module'){ $d8Arguments+=@('--classpath',$XposedApi) }
    Invoke-Native -File $java -Arguments ($d8Arguments+$compiled)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::Open($base,[IO.Compression.ZipArchiveMode]::Update)
    try{foreach($file in Get-ChildItem $dex -Filter '*.dex'){
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$file.FullName,$file.Name,[IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }}finally{$zip.Dispose()}
    $aligned=Join-Path $build 'aligned.apk'
    Invoke-Native -File (Join-Path $BuildToolsDir 'zipalign.exe') -Arguments @('-f','4',$base,$aligned)
    $filename=if($part -eq 'module'){'VirtualSteps-1.1.apk'}else{'StepViewer-1.0.apk'}
    $output=Join-Path $OutputDir $filename
    Invoke-Native -File $java -Arguments @('-jar',(Join-Path $BuildToolsDir 'lib/apksigner.jar'),'sign','--ks',$keystore,'--ks-key-alias','localtest','--ks-pass','pass:android','--key-pass','pass:android','--out',$output,$aligned)
    Invoke-Native -File $java -Arguments @('-jar',(Join-Path $BuildToolsDir 'lib/apksigner.jar'),'verify','--verbose',$output)
    Write-Host "BUILT: $output"
}

#ifndef AppVersion
  #error AppVersion is required
#endif
#ifndef NativeVersion
  #error NativeVersion is required
#endif
#ifndef AppImage
  #error AppImage is required
#endif
#ifndef ReleaseDir
  #error ReleaseDir is required
#endif
#ifndef SourceRoot
  #error SourceRoot is required
#endif

[Setup]
AppId={{195DCD9D-2092-48CA-8C94-F72544782E69}
AppName=Chatty dw6318
AppVersion={#AppVersion}
AppVerName=Chatty dw6318 {#AppVersion}
AppPublisher=dw6318
AppPublisherURL=https://github.com/dw6318/chatty
AppSupportURL=https://github.com/dw6318/chatty/issues
AppUpdatesURL=https://github.com/dw6318/chatty/releases
DefaultDirName={localappdata}\Programs\Chatty-dw6318
DefaultGroupName=Chatty dw6318
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
SourceDir={#AppImage}
OutputDir={#ReleaseDir}
OutputBaseFilename=Chatty_{#AppVersion}_win_x64_setup
SetupIconFile={#SourceRoot}\assets-bundle\Chatty.ico
UninstallDisplayIcon={app}\Chatty-dw6318.exe
VersionInfoVersion={#NativeVersion}
LicenseFile={#SourceRoot}\LICENSE
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
DisableProgramGroupPage=yes
CloseApplications=no
RestartApplications=no

[Files]
Source: "*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; Flags: unchecked

[Icons]
Name: "{group}\Chatty dw6318"; Filename: "{app}\Chatty-dw6318.exe"
Name: "{autodesktop}\Chatty dw6318"; Filename: "{app}\Chatty-dw6318.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Chatty-dw6318.exe"; Description: "Start Chatty dw6318"; Flags: nowait postinstall skipifsilent

@echo off
setlocal EnableExtensions
set "OUTDIR="
set "INPUT="

:parse
if "%~1"=="" goto writePdf
if /I "%~1"=="--outdir" (
  set "OUTDIR=%~2"
  shift
  shift
  goto parse
)
if /I "%~1"=="--convert-to" (
  shift
  shift
  goto parse
)
if /I "%~1"=="--headless" (
  shift
  goto parse
)
set "ARG=%~1"
if /I "%ARG:~0,22%"=="-env:UserInstallation=" (
  shift
  goto parse
)
set "INPUT=%~1"
shift
goto parse

:writePdf
for %%I in ("%INPUT%") do set "TARGET=%OUTDIR%\%%~nI.pdf"
> "%TARGET%" (
  echo %%PDF-1.4
  echo 1 0 obj
  echo ^<^< /Type /Catalog /Pages 2 0 R ^>^>
  echo endobj
  echo 2 0 obj
  echo ^<^< /Type /Pages /Count 1 /Kids [3 0 R] ^>^>
  echo endobj
  echo 3 0 obj
  echo ^<^< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 144] ^>^>
  echo endobj
  echo trailer
  echo ^<^< /Size 4 /Root 1 0 R ^>^>
)

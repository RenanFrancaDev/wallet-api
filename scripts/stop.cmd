@echo off
rem Duplo clique para encerrar a aplicacao (o banco continua ligado). Para desligar o banco tambem, use: .\scripts\stop.ps1 -Banco
cd /d "%~dp0.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop.ps1"
pause

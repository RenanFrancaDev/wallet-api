@echo off
rem Duplo clique para subir tudo (banco + aplicacao). Chama o start.ps1 sem depender da politica de execucao do PowerShell.
cd /d "%~dp0.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1"
rem O pause mantem a janela aberta para dar tempo de ler uma mensagem de erro.
pause

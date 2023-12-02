@echo off
setlocal enabledelayedexpansion

:: Prompt for user input
set /p USER_INPUT_USERNAME=Enter ASA Server Username: 

:: Password input with replacement of quotes
set /p USER_INPUT_PASSWORD=Enter ASA Server Password: 
:: does not work, don't use quotes in password
set USER_INPUT_PASSWORD=!USER_INPUT_PASSWORD:"=\"!

set /p USER_INPUT_HOSTNAME=Enter ASA Server Hostname: 

:: Set environment variables with random suffix
setx ASA_SERVER_USERNAME "%USER_INPUT_USERNAME%"
setx ASA_SERVER_PASSWORD "%USER_INPUT_PASSWORD%"
setx ASA_SERVER_HOSTNAME "%USER_INPUT_HOSTNAME%"




:: Pause the batch file to view the output
pause
endlocal

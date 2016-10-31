@echo off

call ..\config.bat

echo delete database %dbname%
"%mysqlpath%mysql" -u%user% -p%password% -e"drop database %dbname%;"
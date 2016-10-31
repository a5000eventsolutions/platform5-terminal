@echo off

call ..\config.bat

echo Dump database %dbname%
"%mysqlpath%mysqldump" %dbname% -u%user% -p%password% > last.sql
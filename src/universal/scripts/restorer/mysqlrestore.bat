@echo off 

call ..\config.bat

echo Dump old database
"%mysqlpath%mysqldump" %dbname% -u%user% -p%password% > %dbname%.sql-%savedate%-%savetime%
echo delete database
"%mysqlpath%mysql" -u%user% -p%password% -e"drop database %dbname%;"
echo create database if not exist
"%mysqlpath%mysql" -u%user% -p%password% -e"CREATE DATABASE IF NOT EXISTS platform5;"
echo restore backup
"%mysqlpath%mysql" -u%user% -p%password% %dbname% < last.sql
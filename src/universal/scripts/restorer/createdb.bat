@echo off

call ..\config.bat

echo create databaase if not exist
"%mysqlpath%mysql" -u%user% -p%password% -e"CREATE DATABASE IF NOT EXISTS %dbname%;"
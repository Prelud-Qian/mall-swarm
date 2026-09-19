@echo off
rem Seata TC 启动脚本（事务协调者，端口8091）
rem 依赖：D:\seata-run 下的 application.yml（配置）与 cp.txt（依赖清单）
cd /d D:\seata-run
setlocal enabledelayedexpansion
set CP=.
for /f "delims=" %%i in (cp.txt) do set CP=!CP!;%%i
java -cp "!CP!" io.seata.server.ServerApplication

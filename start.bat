@echo off
title Compilador CoinShop v2.0 - Java 17

echo ============================================
echo Compilador do Plugin CoinShop (Integracao CoinCard)
echo ============================================
echo.

echo Procurando Java 17 instalado...
echo.

set JDK_PATH=

rem Procura JDK 17 em locais comuns
for /d %%i in ("C:\Program Files\Java\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Java\jdk17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Eclipse Adoptium\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\AdoptOpenJDK\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\OpenJDK\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Amazon Corretto\jdk17*") do set JDK_PATH=%%i

if "%JDK_PATH%"=="" (
    echo ============================================
    echo ERRO: JDK 17 nao encontrado!
    echo Instale o Java 17 JDK e tente novamente.
    echo ============================================
    pause
    exit /b 1
)

echo Java 17 encontrado em: %JDK_PATH%
echo.

set JAVAC="%JDK_PATH%\bin\javac.exe"
set JAR="%JDK_PATH%\bin\jar.exe"

echo ============================================
echo Preparando ambiente de compilacao...
echo ============================================
echo.

echo Limpando pasta out...
if exist out (
    rmdir /s /q out >nul 2>&1
)
mkdir out
mkdir out\com
mkdir out\com\foxsrv
mkdir out\com\foxsrv\coinshop

echo.
echo ============================================
echo Verificando dependencias...
echo ============================================
echo.

REM Verificar Spigot API
if not exist spigot-api-1.20.1-R0.1-SNAPSHOT.jar (
    echo [ERRO] spigot-api-1.20.1-R0.1-SNAPSHOT.jar nao encontrado!
    echo.
    echo Certifique-se de que o arquivo está na pasta raiz.
    pause
    exit /b 1
) else (
    echo [OK] Spigot API encontrado
)

REM Verificar Gson
if not exist libs\gson-2.10.1.jar (
    echo [ERRO] gson-2.10.1.jar nao encontrado em libs\
    echo.
    echo Certifique-se de que o arquivo está em: libs\gson-2.10.1.jar
    pause
    exit /b 1
) else (
    echo [OK] Gson encontrado em libs\
)

REM Verificar CoinCard API
if not exist CoinCard.jar (
    echo [AVISO] CoinCard.jar nao encontrado na pasta raiz!
    echo O plugin CoinShop requer o CoinCard como dependencia.
    echo.
    echo Certifique-se de que o CoinCard.jar esta na pasta plugins do servidor.
    echo Continuando compilacao mesmo assim...
    echo.
    set COINCARD_PATH=
) else (
    echo [OK] CoinCard API encontrado
    set COINCARD_PATH=CoinCard.jar
)

echo.
echo ============================================
echo Compilando CoinShop...
echo ============================================
echo.

REM Montar classpath
set CLASSPATH="spigot-api-1.20.1-R0.1-SNAPSHOT.jar;libs\gson-2.10.1.jar"
if defined COINCARD_PATH (
    set CLASSPATH=%CLASSPATH%;CoinCard.jar
)

REM Compilar com as dependências necessárias
%JAVAC% --release 17 -d out ^
-classpath %CLASSPATH% ^
-sourcepath src ^
src/com/foxsrv/coinshop/CoinShop.java

if %errorlevel% neq 0 (
    echo ============================================
    echo ERRO AO COMPILAR O PLUGIN!
    echo ============================================
    echo.
    echo Verifique os erros acima e corrija o codigo.
    pause
    exit /b 1
)

echo.
echo Compilacao concluida com sucesso!
echo.

echo ============================================
echo Copiando arquivos de recursos...
echo ============================================
echo.

REM Copiar plugin.yml
if exist resources\plugin.yml (
    copy resources\plugin.yml out\ >nul
    echo [OK] plugin.yml copiado
) else (
    echo [AVISO] plugin.yml nao encontrado em resources\
    echo Criando plugin.yml padrao...
    
    (
        echo name: CoinShop
        echo version: 2.0
        echo main: com.foxsrv.coinshop.CoinShop
        echo api-version: 1.20
        echo author: FoxOficial2
        echo description: A Player Coin Shop plugin with CoinCard integration
        echo depend: [CoinCard]
        echo.
        echo commands:
        echo   coinshop:
        echo     description: Main CoinShop command
        echo     aliases: [cshop]
        echo     usage: /coinshop [reload^|open^|sell^|cancel^|name^|balance]
        echo   cshop:
        echo     description: Alias for coinshop command
        echo     usage: /cshop [reload^|open^|sell^|cancel^|name^|balance]
        echo.
        echo permissions:
        echo   coinshop.*:
        echo     description: All CoinShop permissions
        echo     default: op
        echo     children:
        echo       coinshop.use: true
        echo       coinshop.admin: true
        echo   coinshop.use:
        echo     description: Use basic CoinShop features
        echo     default: true
        echo   coinshop.admin:
        echo     description: Admin commands like reload
        echo     default: op
    ) > out\plugin.yml
    echo [OK] plugin.yml criado automaticamente
)

REM Copiar config.yml
if exist resources\config.yml (
    copy resources\config.yml out\ >nul
    echo [OK] config.yml copiado
) else (
    echo [AVISO] config.yml nao encontrado em resources\
    echo Criando config.yml padrao...
    
    (
        echo # CoinShop Configuration
        echo # Server Card ID for collecting taxes
        echo Server: ""
        echo.
        echo # Tax rate (0.1 = 10%%^)
        echo Tax: 0.1
        echo.
        echo # Minimum and maximum price for items
        echo Min: 0.00000001
        echo Max: 1000.0
        echo.
        echo # Cooldown between transactions in milliseconds
        echo Cooldown: 1100
    ) > out\config.yml
    echo [OK] config.yml criado automaticamente
)

echo.
echo ============================================
echo Criando arquivo JAR...
echo ============================================
echo.

cd out

REM Criar JAR com todos os recursos
%JAR% cf CoinShop.jar com plugin.yml config.yml

cd ..

echo.
echo ============================================
echo PLUGIN COMPILADO COM SUCESSO!
echo ============================================
echo.
echo Arquivo gerado: out\CoinShop.jar
echo.
dir out\CoinShop.jar
echo.
echo ============================================
echo IMPORTANTE - REQUISITOS PARA EXECUCAO:
echo ============================================
echo.
echo 1 - O plugin CoinCard DEVE estar instalado no servidor
echo 2 - Adicione no plugin.xml do CoinCard:
echo     ^<permission^>
echo         ^<name^>coincard.api^</name^>
echo     ^</permission^>
echo 3 - Certifique-se de que ambos os plugins estao na pasta plugins/
echo.
echo ============================================
echo Para instalar:
echo 1 - Copie out\CoinShop.jar para a pasta plugins do servidor
echo 2 - Copie CoinCard.jar para a pasta plugins do servidor (se ainda nao estiver)
echo 3 - Reinicie o servidor ou use /reload confirm
echo ============================================
echo.

pause
# SiusData to PostgreSQL Adapter

## Introduction

The **SiusData to PostgreSQL Adapter** is a simple tool that automatically transfers shooting data from SIUS electronic scoring systems into a PostgreSQL database. This makes it easy for you to store, view, and analyze your shooting data.

## What Does It Do?

- **Monitors a Folder**: Watches a specific folder on your computer where SIUSData saves CSV files containing shooting data.
- **Processes Data**: Reads the data from these CSV files whenever they are created or updated.
- **Stores Data**: Inserts the shooting data into a PostgreSQL database for easy access and analysis.
- **Sends Notifications**: Optionally, it can send you notifications if there are any errors during the process.

## Requirements

To use this tool, you will need:

- **Java**: Version 21 or higher, if using the JAR file version of the application.
- **PostgreSQL Database**: Access to a PostgreSQL database version 15 or higher where the data will be stored.
- **SIUSData Setup with Dongle attached to SIUS scoring system**: The system that generates the CSV files with shooting data.
- **Pushbullet Account** (optional): If you want to receive error notifications on your phone or computer.
- **Gotify Server and App Token** (optional): If you prefer to receive notifications through your Gotify instance.

## Which Version Should You Use?

There are two ways to run the SiusData to PostgreSQL Adapter:

### 1. **JAR File Version (Cross-Platform)**

The JAR file version works on any operating system that has Java 21 or higher installed. This is the most flexible option and works well if you already have Java installed or are comfortable installing it.

- **Advantages**:
    - Works on multiple platforms (Windows, macOS, Linux).
    - Easier to update as new versions of Java come out.

- **Disadvantages**:
    - Requires Java to be installed and set up.
    - May be slower to start compared to the native executable.

### 2. **Native Executable for Windows**

The native executable is optimized for Windows and doesn’t require Java to be installed separately. This option is ideal if you don’t want to manage Java installations or prefer a faster startup time.

- **Advantages**:
    - No need to install Java separately.
    - Starts faster than the JAR version.

- **Disadvantages**:
    - Only works on Windows.
    - May require additional DLLs to run, which are included in the zip download.

## How to Set It Up

### Option 1: Using the JAR File Version

#### Step 1: Download the Application

1. **Obtain the JAR File**: Download the latest version of the [**siusdata-to-postgres-adapter.jar**](https://github.com/martinfrancois/siusdata-to-postgres-adapter/releases/latest).

#### Step 2: Install Java (if not already installed)

1. **Check if Java is Installed**:
    - Press `Win + R`, type `cmd`, and press Enter to open Command Prompt.
    - Type `java -version` and press Enter.
    - If Java is installed, it will display the version number.
    - **Important**: The version number must be **21** or higher. If it shows a lower version, you need to install or update Java to the correct version.

2. **Install or Update Java** (if not installed or version is lower than 21):
    - Download Java 21 or higher from [the official website](https://www.oracle.com/java/technologies/downloads/).
    - Run the installer and follow the on-screen instructions.

#### Step 3: Install PostgreSQL (if not already installed)

Follow the instructions under **"Install PostgreSQL"** further down in this README.

#### Step 4: Prepare the CSV Folder

Follow the instructions under **"Prepare the CSV Folder"** further down in this README.

#### Step 5: Configure the Application

Follow the instructions under **"Configure the Application"** further down in this README to set up environment variables.

#### Step 6: Run the Application

1. **Open Command Prompt**:
    - Press `Win + R`, type `cmd`, and press Enter.
2. **Navigate to the Application Directory**:
    - Use the `cd` command to navigate to the folder where the application JAR file is located.
    - Example:

      ```cmd
      cd C:\path\to\application\folder
      ```

3. **Run the Application**:
    - Type the following command and press Enter:

      ```cmd
      java -jar siusdata-to-postgres-adapter.jar
      ```

    - Replace `siusdata-to-postgres-adapter.jar` with the actual filename if it's different.

---

### Option 2: Using the Native Windows Executable

#### Step 1: Download the Native Executable

1. **Obtain the Native Executable**: Download the latest version of the [**siusdata-to-postgres-adapter-native-executable.zip**](https://github.com/martinfrancois/siusdata-to-postgres-adapter/releases/latest).
 
2. **Extract the Files**:
   - Extract the contents of the ZIP file to a folder of your choice.
   - This ZIP includes the `.exe` file and required `.dll` files.

#### Step 2: Install PostgreSQL (if not already installed)

Follow the instructions under **"Install PostgreSQL"** further down in this README.

#### Step 3: Prepare the CSV Folder

Follow the instructions under **"Prepare the CSV Folder"** further down in this README.

#### Step 4: Configure the Application

Follow the instructions under **"Configure the Application"** further down in this README to set up environment variables.

#### Step 5: Run the Application

1. **Open the Folder**:
   - Navigate to the folder where you extracted the ZIP file.

2. **Run the Executable**:
   - Double-click the `siusdata-to-postgres-adapter.exe` file to start the application.
   - A command-line window will appear, showing the application running.

---

## Automate Application Start and Restart on Failure

To ensure the application starts automatically when you turn on your computer and restarts if it stops or crashes, you can set it up using **Windows Task Scheduler**.

The instructions for setting this up differ slightly for the **JAR file version** and the **native executable version**:

### For the JAR File Version

#### Step 1: Create a Batch File to Run the Application

1. **Open Notepad**:
    - Press `Win + R`, type `notepad`, and press Enter.
2. **Write the Batch Script**:
    - In Notepad, type the following lines:

      ```cmd
      @echo off
      cd C:\path\to\application\folder
      java -jar siusdata-to-postgres-adapter.jar
      ```

      - Replace `C:\path\to\application\folder` with the actual path to your application.
      - Ensure the JAR filename matches your application's filename.
3. **Save the Batch File**:
    - Click **"File"** > **"Save As..."**.
    - In the **"Save as type"** dropdown, select **"All Files (*.*)"**.
    - Enter the filename as `start-siusdata-adapter.bat`.
    - Choose a location to save the batch file (e.g., `C:\path\to\batch\start-siusdata-adapter.bat`).
    - Click **"Save"**.

#### Step 2: Create a Scheduled Task

1. **Open Task Scheduler**:
    - Press `Win + R`, type `taskschd.msc`, and press Enter.
2. **Create a New Task**:
    - In Task Scheduler, click on **"Task Scheduler Library"** in the left pane.
    - Click **"Action"** in the top menu, then select **"Create Task..."**.
3. **General Tab**:
    - **Name**: Enter a name for the task, e.g., `SiusDataAdapter`.
    - **Description**: (Optional) Add a description.
    - **Security Options**:
        - Check **"Run whether user is logged on or not"**.
        - Check **"Run with highest privileges"**.
    - **Configure for**: Select your Windows operating system from the dropdown.
4. **Triggers Tab**:
    - Click **"New..."** to create a new trigger.
    - **Begin the task**: Select **"At startup"** from the dropdown.
    - **Advanced Settings**:
        - Check **"Repeat task every:"** and set it to `1 minute`.
        - Set **"for a duration of:"** to **"Indefinitely"**.
        - Check **"Enabled"**.
    - Click **"OK"**.
5. **Actions Tab**:
    - Click **"New..."** to create a new action.
    - **Action**: Ensure **"Start a program"** is selected.
    - **Program/script**:
        - Click **"Browse..."** and select the batch file you created earlier (`start-siusdata-adapter.bat`).
    - Click **"OK"**.
6. **Conditions Tab**:
    - Uncheck **"Start the task only if the computer is on AC power"** (if applicable).
7. **Settings Tab**:
    - Check **"Allow task to be run on demand"**.
    -

 Check **"If the task fails, restart every:"** and set it to `1 minute`.
    - Set **"Attempt to restart up to:"** `5` times.
    - Check **"If the task is already running, then the following rule applies:"** and select **"Stop the existing instance"**.
    - Click **"OK"**.
8. **Save the Task**:
    - You may be prompted to enter your Windows user password to create the task with the specified security options.
    - Enter your password and click **"OK"**.

#### Step 3: Verify the Scheduled Task

1. **Check Task Scheduler**:
    - Ensure the task `SiusDataAdapter` is listed in the **Task Scheduler Library**.
    - Right-click the task and select **"Run"** to test it.
2. **Verify the Application is Running**:
    - Open Task Manager (press `Ctrl + Shift + Esc`).
    - Look for the Java process or your application in the list of running processes.
    - Check your PostgreSQL database or logs to confirm the application is functioning.

---

### For the Native Executable Version

#### Step 1: Create a Batch File to Run the Native Executable

1. **Open Notepad**:
    - Press `Win + R`, type `notepad`, and press Enter.
2. **Write the Batch Script**:
    - In Notepad, type the following lines:

      ```cmd
      @echo off
      cd C:\path\to\native-executable\folder
      siusdata-to-postgres-adapter.exe
      ```

      - Replace `C:\path\to\native-executable\folder` with the actual path to your application.
3. **Save the Batch File**:
    - Click **"File"** > **"Save As..."**.
    - In the **"Save as type"** dropdown, select **"All Files (*.*)"**.
    - Enter the filename as `start-siusdata-adapter-native.bat`.
    - Choose a location to save the batch file (e.g., `C:\path\to\batch\start-siusdata-adapter-native.bat`).
    - Click **"Save"**.

#### Step 2: Create a Scheduled Task

Follow the exact same steps as in the JAR file version, except when selecting the batch file, point it to the batch file you just created for the native executable (`start-siusdata-adapter-native.bat`).

---

## Install PostgreSQL (if not already installed)

1. **Download PostgreSQL**:
    - Visit [the official PostgreSQL website](https://www.postgresql.org/download/windows/) and download the installer.
2. **Install PostgreSQL**:
    - Run the installer and follow the on-screen instructions.
    - During installation, note down:
        - **Database Username** (default is `postgres`)
        - **Database Password** (you will set this during installation)
        - **Port Number** (default is `5432`)

## Prepare the CSV Folder

1. **Choose a Folder**:
    - Decide on a folder where your SIUS system will save CSV files.
    - Example: `C:\SiusData`.
2. **Configure SIUS System**:
    - Set up SIUSData to export CSV files to this folder.

## Configure the Application

You need to tell the application where to find the CSV files and how to connect to your database. This is done by setting environment variables.

#### Setting Environment Variables on Windows

1. **Open System Properties**:
    - Press `Win + R`, type `sysdm.cpl`, and press Enter.
2. **Access Environment Variables**:
    - In the System Properties window, click on the **"Advanced"** tab.
    - Click on the **"Environment Variables..."** button at the bottom.
3. **Set Environment Variables**:
    - In the **"Environment Variables"** window, under **"System variables"**, click **"New..."** to create a new variable.
    - **Add the following variables one by one**:

      **Variable 1**:  
      - **Variable name**: `CSV_MONITOR_PATH`  
      - **Variable value**: `C:\path\to\your\csv\folder`
        - Replace `C:\path\to\your\csv\folder` with the actual path where your SIUS system saves CSV files.

      **Variable 2**:  
      - **Variable name**: `POSTGRESQL_URL`  
      - **Variable value**: `postgresql://localhost:5432/your_database_name`
        - Replace `your_database_name` with the name of the database you created (e.g., `siusdata`).

      **Variable 3**:  
      - **Variable name**: `POSTGRESQL_USER`  
      - **Variable value**: `your_database_username`
        - Replace `your_database_username` with your PostgreSQL username (default is `postgres`).

      **Variable 4**:  
      - **Variable name**: `POSTGRESQL_PASSWORD`  
      - **Variable value**: `your_database_password`
        - Replace `your_database_password` with your PostgreSQL password.

      **Variable 5 (Optional for Notifications)**:
      - **Variable name**: `PUSHBULLET_API_KEY`
      - **Variable value**: `your_pushbullet_api_key`
        - Replace `your_pushbullet_api_key` with your Pushbullet Access Token.

      **Variable 6 (Optional for Gotify Notifications)**:
      - **Variable name**: `GOTIFY_URL`
      - **Variable value**: `https://your-gotify.example.com`
        - Replace `https://your-gotify.example.com` with the base URL of your Gotify instance.

      **Variable 7 (Optional for Gotify Notifications)**:
      - **Variable name**: `GOTIFY_TOKEN`
      - **Variable value**: `your_gotify_app_token`
        - Replace `your_gotify_app_token` with the token generated for your Gotify app.

      **Variable 8 (Optional Priority Override)**:
      - **Variable name**: `GOTIFY_PRIORITY`
      - **Variable value**: `5`
        - Replace `5` with the priority level you want Gotify to use (defaults to `5` if not set or invalid).

    - After adding each variable, click **"OK"** to save it.

4. **Apply Changes**:
    - Click **"OK"** to close the Environment Variables window.
    - Click **"OK"** again to close the System Properties window.

**Notes**:

- Setting environment variables this way ensures they persist even after you restart your computer.
- Be careful when editing system environment variables. Only change or add the variables specified.

---

## Troubleshooting

- **The Application Doesn't Start**:
    - Ensure Java is installed correctly (if using the JAR version).
    - **Check Java Version**: Use `java -version` in Command Prompt to confirm that the version is **21** or higher.
    - Check that you're using the correct path to the application file.
- **No Data in the Database**:
    - Verify that the SIUS system is saving CSV files in the correct folder.
    - Check that the filenames of the CSV files start with 8 digits and end with `.csv`.
- **Database Connection Errors**:
    - Confirm your PostgreSQL credentials and that the database service is running.
    - Revisit the environment variables to ensure they are set correctly.
- **Pushbullet Notifications Not Working**:
    - Ensure your `PUSHBULLET_API_KEY` is correct.
    - Check your Pushbullet account for any issues.
- **Gotify Notifications Not Working**:
    - Verify that `GOTIFY_URL` points to your Gotify server (including `https://` or `http://`).
    - Ensure the `GOTIFY_TOKEN` matches an active Gotify application token.
    - Optional: Adjust `GOTIFY_PRIORITY` to a supported value on your server.
- **Scheduled Task Issues**:
    - Check Task Scheduler for any errors or history of the task.
    - Ensure the task is set to run with highest privileges.
    - Verify that the task is configured to run whether the user is logged on or not.

---

## Optional: Setting Up Pushbullet Notifications

If you want to receive error notifications:

1. **Sign Up for Pushbullet**:
    - Visit [Pushbullet](https://www.pushbullet.com/) and create an account.
2. **Get Your API Key**:
    - Log in to Pushbullet.
    - Click on your profile picture and select **"Settings"**.
    - Go to the **"Account"** tab.
    - Find **"Access Tokens"** and click **"Create Access Token"**.
    - Copy the generated token.
3. **Set the API Key Environment Variable**:
    - Follow the steps in **"Configure the Application"** to add:

        - **Variable name**: `PUSHBULLET_API_KEY`
        - **Variable value**: Your Pushbullet Access Token

## Optional: Setting Up Gotify Notifications

If you run a Gotify server, you can receive the same error alerts there:

1. **Sign In to Gotify**:
    - Open your Gotify server in a browser and log in as an administrator or user.
2. **Create an Application Token**:
    - Go to the **"Applications"** tab.
    - Click **"Create Application"**, provide a descriptive name, and Gotify will generate a token.
    - Copy the generated token for later use.
3. **Configure Environment Variables**:
    - Add the following variables using the steps from **"Configure the Application"**:

        - **Variable name**: `GOTIFY_URL`
        - **Variable value**: The base URL of your Gotify server (for example, `https://gotify.example.com`).

        - **Variable name**: `GOTIFY_TOKEN`
        - **Variable value**: The application token you generated.

        - **Variable name** (optional): `GOTIFY_PRIORITY`
        - **Variable value**: Priority level for Gotify (defaults to `5` if omitted or invalid).

## Tips

- **Data Backup**:
    - Regularly back up your PostgreSQL database to prevent data loss.
- **Security**:
    - Keep your database credentials secure.
    - Do not share your Pushbullet API key.

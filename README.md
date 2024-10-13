# SiusData to PostgreSQL Adapter

## Introduction

The **SiusData to PostgreSQL Adapter** is a simple tool that automatically transfers shooting data from SIUS electronic scoring systems into a PostgreSQL database. This makes it easy for you to store, view, and analyze your shooting data.

## What Does It Do?

- **Monitors a Folder**: Watches a specific folder on your computer where SIUSData saves CSV files containing shooting data.
- **Processes Data**: Reads the data from these CSV files whenever they are created or updated.
- **Stores Data**: Inserts the shooting data into a PostgreSQL database for easy access and analysis.
- **Sends Notifications**: Optionally, it can send you notifications if there are any errors during the process.

## Requirements

To use this tool on **Windows**, you need:

- **Java**: Version 21 or higher installed on your computer.
- **PostgreSQL Database**: Access to a PostgreSQL database version 15 or higher where the data will be stored.
- **SIUSData Setup with Dongle attached to SIUS scoring system**: The system that generates the CSV files with shooting data.
- **Pushbullet Account** (optional): If you want to receive error notifications on your phone or computer.

## How to Set It Up

### Step 1: Download the Application

1. **Obtain the Adapter**: Download the latest version of the **SiusData to PostgreSQL Adapter**. (TODO add link)

### Step 2: Install Java (if not already installed)

1. **Check if Java is Installed**:
    - Press `Win + R`, type `cmd`, and press Enter to open Command Prompt.
    - Type `java -version` and press Enter.
    - If Java is installed, it will display the version number.
2. **Install Java** (if not installed):
    - Download Java from [the official website](https://www.java.com/en/download/).
    - Run the installer and follow the on-screen instructions.

### Step 3: Install PostgreSQL (if not already installed)

1. **Download PostgreSQL**:
    - Visit [the official PostgreSQL website](https://www.postgresql.org/download/windows/) and download the installer.
2. **Install PostgreSQL**:
    - Run the installer and follow the on-screen instructions.
    - During installation, note down:
        - **Database Username** (default is `postgres`)
        - **Database Password** (you will set this during installation)
        - **Port Number** (default is `5432`)

### Step 4: Create a Database in PostgreSQL

1. **Open pgAdmin**:
    - Find pgAdmin in your Start Menu and open it.
2. **Create a New Database**:
    - Right-click on "Databases" in the left panel.
    - Select "Create" > "Database...".
    - Enter a name for your database (e.g., `siusdata`).
    - Click "Save".

### Step 5: Prepare the CSV Folder

1. **Choose a Folder**:
    - Decide on a folder where your SIUS system will save CSV files.
    - Example: `C:\SiusData`.
2. **Configure SIUS System**:
    - Set up SIUSData to export CSV files to this folder.

### Step 6: Configure the Application

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

      **Variable 1:**

        - **Variable name**: `CSV_MONITOR_PATH`
        - **Variable value**: `C:\path\to\your\csv\folder`
            - Replace `C:\path\to\your\csv\folder` with the actual path where your SIUS system saves CSV files.

      **Variable 2:**

        - **Variable name**: `POSTGRESQL_URL`
        - **Variable value**: `postgresql://localhost:5432/your_database_name`
            - Replace `your_database_name` with the name of the database you created (e.g., `siusdata`).

      **Variable 3:**

        - **Variable name**: `POSTGRESQL_USER`
        - **Variable value**: `your_database_username`
            - Replace `your_database_username` with your PostgreSQL username (default is `postgres`).

      **Variable 4:**

        - **Variable name**: `POSTGRESQL_PASSWORD`
        - **Variable value**: `your_database_password`
            - Replace `your_database_password` with your PostgreSQL password.

      **Variable 5 (Optional for Notifications):**

        - **Variable name**: `PUSHBULLET_API_KEY`
        - **Variable value**: `your_pushbullet_api_key`
            - Replace `your_pushbullet_api_key` with your Pushbullet Access Token.

    - After adding each variable, click **"OK"** to save it.

4. **Apply Changes**:
    - Click **"OK"** to close the Environment Variables window.
    - Click **"OK"** again to close the System Properties window.

**Notes:**

- Setting environment variables this way ensures they persist even after you restart your computer.
- Be careful when editing system environment variables. Only change or add the variables specified.

### Step 7: Run the Application

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

4. **Keep the Application Running**:
    - Leave the Command Prompt window open.
    - You can minimize it, but do not close it, as the application needs to keep running to monitor the folder.

### Optional: Automate Application Start and Restart on Failure

To ensure the application starts automatically when you turn on your computer and restarts if it stops or crashes, you can set it up using **Windows Task Scheduler**.

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
    - Check **"If the task fails, restart every:"** and set it to `1 minute`.
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

#### Step 4: Ensure the Application Restarts on Failure

With the settings configured in the **Settings** tab, Windows Task Scheduler will attempt to restart the application if it stops unexpectedly.

- **Restart on Failure**:
    - The task is set to restart every `1` minute if it fails.
    - It will attempt to restart up to `5` times.
- **Continuous Monitoring**:
    - The trigger set to **"At startup"** with a repeat interval ensures the task checks every minute to see if it needs to start or restart the application.

**Note**: The batch file runs the application and keeps it running as long as the Java process is active. If the application crashes, the scheduled task settings ensure it is restarted.

## How It Works

- **Monitoring**: The application watches the folder you specified for new or updated CSV files.
- **Processing**: When a CSV file is added or changed, it reads the new data.
- **Storing Data**: The data is inserted into your PostgreSQL database in structured tables.
- **Notifications**: If there's an error, and you've set up Pushbullet, you'll receive a notification.

## Viewing Your Data

- **Using pgAdmin or Other Tools**:
    - Open pgAdmin or any PostgreSQL client.
    - Connect to your database using the credentials you set up.
    - The data is stored in tables named `siusdata_shots` and `file_progress`.
- **Analyzing Data**:
    - You can run queries, generate reports, or use other software to analyze your shooting data.

## Troubleshooting

- **The Application Doesn't Start**:
    - Ensure Java is installed correctly.
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
- **Scheduled Task Issues**:
    - Check Task Scheduler for any errors or history of the task.
    - Ensure the task is set to run with highest privileges.
    - Verify that the task is configured to run whether the user is logged on or not.

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
    - Follow the steps in **Setting Environment Variables on Windows** to add:

        - **Variable name**: `PUSHBULLET_API_KEY`
        - **Variable value**: Your Pushbullet Access Token

## Tips

- **Data Backup**:
    - Regularly back up your PostgreSQL database to prevent data loss.
- **Security**:
    - Keep your database credentials secure.
    - Do not share your Pushbullet API key.
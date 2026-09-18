# Builder Toolbox

A client-side Meteor Client addon for Minecraft 1.21.11.

## Requirements

- Java 21 JDK
- Fabric Loader 0.18.2 or newer
- Meteor Client for Minecraft 1.21.11

## Development

Open this directory as a Gradle project in IntelliJ IDEA, then run the `Minecraft Client` run configuration to test the addon.

Build a distributable JAR on Windows with:

```powershell
.\gradlew.bat build
```

The built JAR is written to `build/libs`. Install it in the same `mods` folder as Meteor Client.

## Project layout

- `src/main/java/com/buildertoolbox/BuilderToolboxAddon.java` is the Meteor addon entry point and category registration point.
- `src/main/resources/fabric.mod.json` defines the Fabric and Meteor metadata.
- `gradle/libs.versions.toml` pins Minecraft, Yarn mappings, Fabric Loader, Java, Loom, and Meteor versions.

The project is initialized from the official [Meteor Addon Template](https://github.com/MeteorDevelopment/meteor-addon-template), then adjusted for Minecraft 1.21.11.

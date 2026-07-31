# teamcity-diversion

TeamCity VCS plugin for [Diversion](https://www.diversion.dev) version control system.

> **This is a fork of [midgen/teamcity-diversion](https://github.com/midgen/teamcity-diversion).**
> It fixes polling on non-default branches: upstream resolves the head of the repository's
> default branch no matter which branch a VCS root is configured for, so pushes to any other
> branch never trigger a build. If you are monitoring a branch other than your repository's
> default, you need this build rather than upstream.

## Summary

This is a very rough, basic working version of a Diversion VCS plugin for TeamCity. It does give you first class VCS functionality in TeamCity for Diversion, rather than relying on PowerShell scripts, with change tracking, change details withing TeamCity, patches, server and agent side checkout.

The plugin does not use the Diversion API, it just wraps the dv CLI client so you will need that configured and logged in on your server and build agents.

Currently only tested on Windows. Polling has been validated against both a default and a
non-default branch.

## Current Status

**✅ Working Features:**
- ✅ **Change Detection**: Automatically detects new commits and triggers builds
- ✅ **File-Level Changes**: Shows actual changed files with status (added/modified/deleted)
- ✅ **Server-Side Checkout**: Build patches on TeamCity server (requires working directory)
- ✅ **Agent-Side Checkout**: Full support for `dv` commands on agents
- ✅ **Branch Monitoring**: Track specific branches for changes, including non-default branches
- ✅ **Build Triggering**: New commits automatically trigger configured builds
- ✅ **Version Display**: Shows commit numbers in TeamCity UI

**⚠️ Known Limitations:**
- ⚠️ **Single Branch Only**: Each VCS root monitors one branch at a time

## Requirements

### Server
- TeamCity 2023.11 or later
- Java 8 or later
- Diversion CLI (`dv`) installed for change detection

### Build Agents (for Agent-Side Checkout)
- Diversion CLI (`dv`) installed and available in PATH
- Diversion authentication configured (`dv login` must be run)
- **Note:** Required only if using agent-side checkout mode

## Installation

1. Download the plugin ZIP from the releases page or build from source
2. Upload to TeamCity: **Administration → Plugins → Upload plugin zip**
3. Restart TeamCity server
4. **Install Diversion CLI on TeamCity server** (for change detection, required for both checkout modes)
5. **Clone your Diversion workspace to your server **
6. **Configure your VCSRoot** (select Diversion from the list, set your repo ID and working directory to the workspace you cloned in the previous step)
5. **Run `dv login` on server**
6. **For Agent-Side Checkout**: Install Diversion CLI on all build agents and run `dv login` on each agent

## Building from Source

```bash
mvn clean package
```

The plugin ZIP will be created at: `build/target/teamcity-diversion-vcs.zip`

## Configuration

### VCS Root Settings

- **Repository ID**: Your Diversion repository ID (format: `dv.repo.<uuid>`)
- **Branch Name**: The branch to monitor (default: `main`)
- **DV Executable Path**: Path to `dv` executable (default: `dv`)
- **Working Directory**: Path to your diversion workspace on your server

### Finding Your Repository ID

```bash
dv repo
```

This will display your repository ID in the format `dv.repo.<uuid>`.

## How It Works

### Change Detection
1. TeamCity polls the Diversion repository for new commits
2. Plugin uses `dv branch` to get the latest commit on the monitored branch
3. Plugin uses `dv ls <commitId> <file>` to get file-level changes with status codes
4. If new commits are detected, TeamCity triggers configured builds showing actual changed files

### Checkout Methods

Both server-side and agent-side checkout are supported. Choose the mode that best fits your TeamCity configuration:

**Server-Side Checkout**:
- TeamCity server builds patches and sends them to agents
- Requires **Working Directory** to be configured in VCS root settings
- Server uses `dv checkout` to retrieve file contents for patch building
- Agents receive patches and don't need Diversion CLI installed
- Best for environments where installing CLI on agents is impractical

**Agent-Side Checkout**:
- Agents perform checkout directly using Diversion CLI
- Agent runs `dv clone` on first checkout
- Subsequent checkouts use `dv update` and `dv checkout` for fast incremental updates
- Requires Diversion CLI installed and authenticated on all agents
- Best for distributed teams where agents have full CLI access

> **The VCS root's Working Directory must be a dedicated Diversion workspace.**
> The poll path runs `dv checkout --discard-changes` against it, so any uncommitted work
> there is destroyed. In particular, never point it at a build's checkout directory: with
> server-side checkout TeamCity wipes that directory on every clean checkout, and Diversion
> records the wipe as a mass deletion. One observed setup reached 21,068 pending deletions
> this way. If your build steps run `dv` themselves, set the build to
> "Do not checkout files automatically" and let the VCS root serve only change detection.

> **Agent-side checkout needs no manual cleanup.** Upstream, a clean checkout deleted the
> checkout directory and re-cloned, but the delete failed while the workspace was still
> registered, and the clone then exited 3. Since TeamCity forces a clean checkout after a
> failed one, a single failure wedged the build configuration permanently. This fork
> releases the workspace with `dv unregister` before deleting, and aborts with a real
> message instead of cloning into a directory that survived deletion.

## Project Structure

```
TCDiversion/
├── server/              Server-side plugin (change detection, patch building)
├── agent/               Agent-side plugin (workspace checkout)
├── build/               Plugin packaging
├── teamcity-plugin.xml  Plugin descriptor
└── pom.xml             Maven configuration
```

## Known Limitations

- **Single branch monitoring**: Each VCS root can only monitor one branch at a time
- **No merge request integration**: Does not integrate with Diversion code review features

## Development

### Local Development Setup

1. Build the plugin: `mvn clean package`
2. Copy to TeamCity plugins directory:
   ```bash
   copy build\target\teamcity-diversion-vcs.zip D:\TeamCityData\plugins\
   ```
3. Restart TeamCity server
4. Check logs: `D:\TeamCity\logs\teamcity-server.log`

## Contributing

Contributions are welcome! Please ensure all changes include:
- Updated documentation
- Tested functionality
- Clean commit messages

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Support

For Diversion-specific questions, join the [Diversion Discord](https://discord.gg/9UtVyDkPS2).

For TeamCity plugin issues, please open a GitHub issue.

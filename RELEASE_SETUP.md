# Google Play Store Release Setup

This document outlines the steps to configure automated releases to the Google Play Store.

## Prerequisites

1. **Google Play Console Account**: Ensure you have a Google Play Console developer account
2. **App Registration**: Your app must be registered in Google Play Console
3. **Google Play App Signing**: Enable Google Play App Signing for your app (recommended)

## Required GitHub Secrets

Add these secrets to your GitHub repository settings (`Settings > Secrets and variables > Actions`):

### `SERVICE_ACCOUNT_JSON`
1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select existing project
3. Enable the Google Play Android Developer API
4. Create a service account:
   - Go to `IAM & Admin > Service Accounts`
   - Click `Create Service Account`
   - Fill in the details and click `Create`
   - Skip granting roles for now, click `Done`
5. Generate and download the JSON key:
   - Click on the created service account
   - Go to `Keys` tab
   - Click `Add Key > Create New Key`
   - Select `JSON` and click `Create`
   - Download the JSON file
6. Link the service account to Google Play Console:
   - Go to [Google Play Console](https://play.google.com/console)
   - Select your app
   - Go to `Setup > API Access`
   - Click `Link` next to Google Cloud Project
   - Select your project and click `Link`
   - Grant access to the service account:
     - Find your service account in the list
     - Click `Grant Access`
     - Select appropriate permissions (Release Manager recommended)
7. Copy the entire JSON file content and paste it as the `SERVICE_ACCOUNT_JSON` secret

## Package Name Configuration

Update the `packageName` in `.github/workflows/release.yml` to match your app's package name:

```yaml
packageName: com.arunderwood.heightmark  # Update this to your actual package name
```

## Release Track Configuration

The workflow is configured to release to the `internal` track by default. You can change this in the workflow file:

- `internal`: Internal testing track
- `alpha`: Alpha testing track  
- `beta`: Beta testing track
- `production`: Production track (live on Play Store)

## Creating a Release

1. **Update Version**: Update version in `app/build.gradle.kts`:
   ```kotlin
   versionCode = 2
   versionName = "1.1.0"
   ```

2. **Update Release Notes**: Edit `metadata/whatsnew/whatsnew-en-US` with your release notes

3. **Create and Push Tag**:
   ```bash
   git tag v1.1.0
   git push origin v1.1.0
   ```

4. **Monitor Release**: Check the GitHub Actions tab for the release workflow progress

## Workflow Features

- **Automatic Builds**: Builds release AAB on version tags
- **Quality Checks**: Runs lint and unit tests before release
- **Play Store Upload**: Uploads AAB to specified track
- **GitHub Release**: Creates GitHub release with changelog
- **Artifact Storage**: Saves build artifacts for 30 days

## Troubleshooting

### Common Issues

1. **Invalid Package Name**: Ensure package name matches exactly with Play Console
2. **Service Account Permissions**: Service account needs "Release Manager" role
3. **API Not Enabled**: Ensure Google Play Android Developer API is enabled
4. **First Release**: First release may need to be done manually through Play Console

### Debug Steps

1. Check GitHub Actions logs for detailed error messages
2. Verify service account JSON format and permissions
3. Ensure Google Play Console app is properly configured
4. Test service account access using Play Console API

## Security Notes

- Never commit service account JSON files to your repository
- Use GitHub Secrets for all sensitive information
- Regularly rotate service account keys
- Monitor API usage in Google Cloud Console

## Support

For issues with:
- **Google Play Console**: Check [Google Play Console Help](https://support.google.com/googleplay/android-developer)
- **GitHub Actions**: Check [GitHub Actions Documentation](https://docs.github.com/en/actions)
- **Upload Action**: Check [upload-google-play Action](https://github.com/r0adkll/upload-google-play)
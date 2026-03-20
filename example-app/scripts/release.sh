#!/bin/bash
set -e

# Check npm login status
echo "Checking npm login status..."
if ! npm whoami &>/dev/null; then
  echo "Not logged in to npm. Logging in..."
  npm login
fi
echo "Logged in as: $(npm whoami)"

# Ask for release type
echo ""
echo "Select release type:"
echo "  1) patch (bugfix)"
echo "  2) minor"
echo "  3) major"
read -rp "Enter choice [1-3]: " choice

case "$choice" in
  1) release_type="patch" ;;
  2) release_type="minor" ;;
  3) release_type="major" ;;
  *) echo "Invalid choice"; exit 1 ;;
esac

# Bump version
new_version=$(npm version "$release_type" --no-git-tag-version)
echo "Version bumped to $new_version"

# Build
echo "Building..."
npm run build

# Publish
echo "Publishing $new_version to npm..."
npm publish --access public

echo "Released $new_version"

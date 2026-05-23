#!/bin/bash

# Build Android APK with Vulkan GPU acceleration
# Requires: Vulkan SDK installed with glslc in PATH

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}Building Flash Android with Vulkan GPU acceleration...${NC}"

# Check for glslc
if ! command -v glslc &> /dev/null; then
    echo -e "${RED}Error: glslc not found in PATH${NC}"
    echo "Install Vulkan SDK from https://vulkan.lunarg.com/"
    echo "Or on Ubuntu: sudo apt install vulkan-sdk"
    exit 1
fi

echo -e "${GREEN}✓ glslc found: $(which glslc)${NC}"

# Check Vulkan SDK version
VULKAN_VERSION=$(glslc --version | head -n1)
echo -e "${GREEN}✓ Vulkan SDK: $VULKAN_VERSION${NC}"

# Clean previous build
echo "Cleaning previous build..."
./gradlew clean

# Build with Vulkan enabled
echo "Building with ENABLE_VULKAN_ANDROID=ON..."
./gradlew :composeApp:assembleDebug \
    -PENABLE_VULKAN_ANDROID=true \
    --info | grep -E "(Vulkan|GGML_VULKAN|glslc)"

BUILD_SUCCESS=$?

if [ $BUILD_SUCCESS -eq 0 ]; then
    echo -e "${GREEN}✓ Build completed successfully with Vulkan support${NC}"
    echo ""
    echo "APK location: composeApp/build/outputs/apk/debug/"
    echo ""
    echo -e "${YELLOW}To verify Vulkan is working:${NC}"
    echo "1. Install and run the APK"
    echo "2. Check logs for 'using device Vulkan0' and layer offload counts"
    echo "3. Compare TPS performance vs CPU-only build"
else
    echo -e "${RED}✗ Build failed${NC}"
    echo "Check build logs above for glslc or Vulkan-related errors"
    exit 1
fi
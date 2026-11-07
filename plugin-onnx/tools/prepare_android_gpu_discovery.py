"""Adapt the pinned optional EP to Android GPUs that lack desktop DRM/PCI discovery.

Enumerate a real hardware Vulkan adapter through the plugin's bundled Dawn. Do
not invent a GPU device or enable the software-adapter escape hatch.
"""
import hashlib
import tarfile


def prepare(source, archive):
    root = source / "onnxruntime/core/providers/webgpu/ep"
    header = root / "factory.h"
    implementation = root / "factory.cc"
    originals = {
        header: "e0051c82313732424895552e1dcbddcb679979c6aed49e327e8b452bf8dc8247",
        implementation: "45a245a547e23c432652f7a198e821f6252e6472b290ff647c234350c53fca6e",
    }
    for path, expected in originals.items():
        with tarfile.open(archive) as contents:
            content = contents.extractfile(source.name + "/" + str(path.relative_to(source))).read().decode()
        if hashlib.sha256(content.encode()).hexdigest() != expected:
            raise RuntimeError(f"Unexpected pinned source: {path}")
        if path == header:
            content = content.replace("  OrtHardwareDevice* virtual_hw_device_ = nullptr;", """  OrtHardwareDevice* virtual_hw_device_ = nullptr;
  // CaraML: owned hardware adapter discovered through Dawn on Android.
  OrtHardwareDevice* caraml_android_hw_device_ = nullptr;""")
        else:
            content = content.replace('#include <algorithm>', '''#include <algorithm>
#if defined(__ANDROID__)
#include "dawn/native/DawnNative.h"
#endif''')
            content = content.replace("Factory::~Factory() {", """Factory::~Factory() {
  if (caraml_android_hw_device_ != nullptr) {
    Api().ep.ReleaseHardwareDevice(caraml_android_hw_device_);
  }""")
            anchor = "  // Fall back to advertising WebGPU against the CPU device when no GPU-backed WebGPU EP device was added."
            content = content.replace(anchor, '''#if defined(__ANDROID__)
  // Mobile GPUs need not expose the desktop DRM/PCI paths used by ORT.
  if (num_ep_devices == 0 && max_ep_devices > 0) {
    if (factory->caraml_android_hw_device_ == nullptr) {
      dawn::native::Instance instance;
      WGPURequestAdapterOptions options = WGPU_REQUEST_ADAPTER_OPTIONS_INIT;
      options.backendType = WGPUBackendType_Vulkan;
      const auto& procs = dawn::native::GetProcs();
      for (const auto& adapter : instance.EnumerateAdapters(&options)) {
        WGPUAdapterInfo info = WGPU_ADAPTER_INFO_INIT;
        if (procs.adapterGetInfo(adapter.Get(), &info) != WGPUStatus_Success) continue;
        const bool hardware = info.adapterType == WGPUAdapterType_IntegratedGPU ||
                              info.adapterType == WGPUAdapterType_DiscreteGPU;
        OrtStatus* status = nullptr;
        if (hardware) {
          const std::string name = info.device.data ? std::string(info.device.data, info.device.length) : "Unknown";
          const std::string vendor = info.vendor.data ? std::string(info.vendor.data, info.vendor.length) : "Unknown";
          Ort::KeyValuePairs metadata;
          metadata.Add("name", name.c_str());
          metadata.Add("discovery", "Dawn Vulkan hardware adapter");
          status = Api().ep.CreateHardwareDevice(OrtHardwareDeviceType_GPU,
              info.vendorID, info.deviceID, vendor.c_str(), metadata,
              &factory->caraml_android_hw_device_);
        }
        procs.adapterInfoFreeMembers(info);
        ORT_API_RETURN_IF_ERROR(status);
        if (hardware) break;
      }
    }
    if (factory->caraml_android_hw_device_ != nullptr) {
      OrtEpDevice* ep_device = nullptr;
      ORT_API_RETURN_IF_ERROR(Api().ep.CreateEpDevice(this_ptr,
          factory->caraml_android_hw_device_, nullptr, nullptr, &ep_device));
      ORT_API_RETURN_IF_ERROR(Api().ep.EpDevice_AddAllocatorInfo(ep_device, factory->default_memory_info_));
      ORT_API_RETURN_IF_ERROR(Api().ep.EpDevice_AddAllocatorInfo(ep_device, factory->readonly_memory_info_));
      ep_devices[num_ep_devices++] = ep_device;
    }
  }
#endif

''' + anchor)
        if path.read_text() != content:
            path.write_text(content)

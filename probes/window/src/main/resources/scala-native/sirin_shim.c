// Pointer-only shim between Scala Native and wgpu-native / SDL3 (VISION §6.1).
//
// Scala Native externs cannot pass or return C structs by value, and webgpu.h
// uses by-value structs for futures, callback-info records and string views.
// Every such function gets a wrapper here that takes and returns pointers.
// Callbacks record results into caller-owned native memory and never call
// back into Scala, so no Scala code runs inside wgpuInstanceProcessEvents.

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include <SDL3/SDL.h>
#include <SDL3/SDL_metal.h>
#include <webgpu/webgpu.h>

#ifdef __APPLE__
#include <mach/mach.h>
#endif

// ---------------------------------------------------------------------------
// Request results (adapter, device)

#define SIRIN_MESSAGE_CAP 512

typedef struct sirin_request_result {
  uint32_t done;
  uint32_t status;
  void *object;
  char message[SIRIN_MESSAGE_CAP];
} sirin_request_result;

static void sirin_copy_message(sirin_request_result *r, WGPUStringView m) {
  size_t n = 0;
  if (m.data != NULL) {
    n = (m.length == WGPU_STRLEN) ? strlen(m.data) : m.length;
    if (n >= SIRIN_MESSAGE_CAP) n = SIRIN_MESSAGE_CAP - 1;
    memcpy(r->message, m.data, n);
  }
  r->message[n] = '\0';
}

static void sirin_on_adapter(WGPURequestAdapterStatus status, WGPUAdapter adapter,
                             WGPUStringView message, void *userdata1, void *userdata2) {
  (void)userdata2;
  sirin_request_result *r = (sirin_request_result *)userdata1;
  r->status = (uint32_t)status;
  r->object = adapter;
  sirin_copy_message(r, message);
  r->done = 1;
}

static void sirin_on_device(WGPURequestDeviceStatus status, WGPUDevice device,
                            WGPUStringView message, void *userdata1, void *userdata2) {
  (void)userdata2;
  sirin_request_result *r = (sirin_request_result *)userdata1;
  r->status = (uint32_t)status;
  r->object = device;
  sirin_copy_message(r, message);
  r->done = 1;
}

static void sirin_on_uncaptured_error(WGPUDevice const *device, WGPUErrorType type,
                                      WGPUStringView message, void *userdata1,
                                      void *userdata2) {
  (void)device; (void)userdata1; (void)userdata2;
  fprintf(stderr, "[wgpu uncaptured error %d] %.*s\n", (int)type,
          message.data ? (int)(message.length == WGPU_STRLEN ? strlen(message.data)
                                                             : message.length)
                       : 0,
          message.data ? message.data : "");
}

void sirin_wgpu_request_adapter(WGPUInstance instance, WGPURequestAdapterOptions const *options,
                                sirin_request_result *out, uint64_t *future_id) {
  WGPURequestAdapterCallbackInfo info = {0};
  info.mode = WGPUCallbackMode_AllowProcessEvents;
  info.callback = sirin_on_adapter;
  info.userdata1 = out;
  *future_id = wgpuInstanceRequestAdapter(instance, options, info).id;
}

void sirin_wgpu_request_device(WGPUAdapter adapter, sirin_request_result *out,
                               uint64_t *future_id) {
  WGPUDeviceDescriptor desc = {0};
  desc.uncapturedErrorCallbackInfo.callback = sirin_on_uncaptured_error;
  WGPURequestDeviceCallbackInfo info = {0};
  info.mode = WGPUCallbackMode_AllowProcessEvents;
  info.callback = sirin_on_device;
  info.userdata1 = out;
  *future_id = wgpuAdapterRequestDevice(adapter, &desc, info).id;
}

void sirin_wgpu_surface_capabilities_free(WGPUSurfaceCapabilities *caps) {
  wgpuSurfaceCapabilitiesFreeMembers(*caps);
}

// ---------------------------------------------------------------------------
// By-value round trip used by the ABI test: a struct passed by value through C
// must arrive intact.

static WGPUColor sirin_echo_color_value(WGPUColor c) { return c; }

void sirin_abi_echo_color(WGPUColor const *in, WGPUColor *out) {
  *out = sirin_echo_color_value(*in);
}

// ---------------------------------------------------------------------------
// ABI layout export: out[0] = sizeof, out[1..n] = offsetof each field in order.
// Returns the number of values written.

#define F(T, f) out[i++] = offsetof(T, f)
#define BEGIN(T) size_t i = 0; out[i++] = sizeof(T)

size_t sirin_abi_layout(int which, size_t *out) {
  switch (which) {
  case 0: { BEGIN(WGPUChainedStruct); F(WGPUChainedStruct, next); F(WGPUChainedStruct, sType); return i; }
  case 1: { BEGIN(WGPUStringView); F(WGPUStringView, data); F(WGPUStringView, length); return i; }
  case 2: { BEGIN(WGPUSurfaceSourceMetalLayer); F(WGPUSurfaceSourceMetalLayer, chain); F(WGPUSurfaceSourceMetalLayer, layer); return i; }
  case 3: { BEGIN(WGPUSurfaceDescriptor); F(WGPUSurfaceDescriptor, nextInChain); F(WGPUSurfaceDescriptor, label); return i; }
  case 4: {
    BEGIN(WGPUSurfaceConfiguration);
    F(WGPUSurfaceConfiguration, nextInChain); F(WGPUSurfaceConfiguration, device);
    F(WGPUSurfaceConfiguration, format); F(WGPUSurfaceConfiguration, usage);
    F(WGPUSurfaceConfiguration, width); F(WGPUSurfaceConfiguration, height);
    F(WGPUSurfaceConfiguration, viewFormatCount); F(WGPUSurfaceConfiguration, viewFormats);
    F(WGPUSurfaceConfiguration, alphaMode); F(WGPUSurfaceConfiguration, presentMode);
    return i;
  }
  case 5: { BEGIN(WGPUSurfaceTexture); F(WGPUSurfaceTexture, nextInChain); F(WGPUSurfaceTexture, texture); F(WGPUSurfaceTexture, status); return i; }
  case 6: { BEGIN(WGPUColor); F(WGPUColor, r); F(WGPUColor, g); F(WGPUColor, b); F(WGPUColor, a); return i; }
  case 7: {
    BEGIN(WGPURenderPassColorAttachment);
    F(WGPURenderPassColorAttachment, nextInChain); F(WGPURenderPassColorAttachment, view);
    F(WGPURenderPassColorAttachment, depthSlice); F(WGPURenderPassColorAttachment, resolveTarget);
    F(WGPURenderPassColorAttachment, loadOp); F(WGPURenderPassColorAttachment, storeOp);
    F(WGPURenderPassColorAttachment, clearValue);
    return i;
  }
  case 8: {
    BEGIN(WGPURenderPassDescriptor);
    F(WGPURenderPassDescriptor, nextInChain); F(WGPURenderPassDescriptor, label);
    F(WGPURenderPassDescriptor, colorAttachmentCount); F(WGPURenderPassDescriptor, colorAttachments);
    F(WGPURenderPassDescriptor, depthStencilAttachment); F(WGPURenderPassDescriptor, occlusionQuerySet);
    F(WGPURenderPassDescriptor, timestampWrites);
    return i;
  }
  case 9: {
    BEGIN(WGPUSurfaceCapabilities);
    F(WGPUSurfaceCapabilities, nextInChain); F(WGPUSurfaceCapabilities, usages);
    F(WGPUSurfaceCapabilities, formatCount); F(WGPUSurfaceCapabilities, formats);
    F(WGPUSurfaceCapabilities, presentModeCount); F(WGPUSurfaceCapabilities, presentModes);
    F(WGPUSurfaceCapabilities, alphaModeCount); F(WGPUSurfaceCapabilities, alphaModes);
    return i;
  }
  case 10: {
    BEGIN(WGPURequestAdapterOptions);
    F(WGPURequestAdapterOptions, nextInChain); F(WGPURequestAdapterOptions, featureLevel);
    F(WGPURequestAdapterOptions, powerPreference); F(WGPURequestAdapterOptions, forceFallbackAdapter);
    F(WGPURequestAdapterOptions, backendType); F(WGPURequestAdapterOptions, compatibleSurface);
    return i;
  }
  case 11: { BEGIN(sirin_request_result); F(sirin_request_result, done); F(sirin_request_result, status); F(sirin_request_result, object); F(sirin_request_result, message); return i; }
  default: return 0;
  }
}

#undef F
#undef BEGIN

// ---------------------------------------------------------------------------
// SDL constants, read from the headers rather than transcribed into Scala.

enum {
  SIRIN_SDL_EVENT_QUIT = 0,
  SIRIN_SDL_EVENT_WINDOW_EXPOSED,
  SIRIN_SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED,
  SIRIN_SDL_EVENT_WINDOW_CLOSE_REQUESTED,
  SIRIN_SDL_EVENT_WINDOW_DISPLAY_SCALE_CHANGED,
  SIRIN_SDL_EVENT_KEY_DOWN,
  SIRIN_SDL_KEY_OFFSET,
  SIRIN_SDL_KEY_ESCAPE,
  SIRIN_SDL_EVENT_SIZE,
  SIRIN_SDL_CONSTANT_COUNT
};

size_t sirin_sdl_constants(uint64_t *out) {
  out[SIRIN_SDL_EVENT_QUIT] = SDL_EVENT_QUIT;
  out[SIRIN_SDL_EVENT_WINDOW_EXPOSED] = SDL_EVENT_WINDOW_EXPOSED;
  out[SIRIN_SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED] = SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED;
  out[SIRIN_SDL_EVENT_WINDOW_CLOSE_REQUESTED] = SDL_EVENT_WINDOW_CLOSE_REQUESTED;
  out[SIRIN_SDL_EVENT_WINDOW_DISPLAY_SCALE_CHANGED] = SDL_EVENT_WINDOW_DISPLAY_SCALE_CHANGED;
  out[SIRIN_SDL_EVENT_KEY_DOWN] = SDL_EVENT_KEY_DOWN;
  out[SIRIN_SDL_KEY_OFFSET] = offsetof(SDL_KeyboardEvent, key);
  out[SIRIN_SDL_KEY_ESCAPE] = SDLK_ESCAPE;
  out[SIRIN_SDL_EVENT_SIZE] = sizeof(SDL_Event);
  return SIRIN_SDL_CONSTANT_COUNT;
}

// ---------------------------------------------------------------------------
// Process statistics for leak checks.

uint64_t sirin_resident_bytes(void) {
#ifdef __APPLE__
  mach_task_basic_info_data_t info;
  mach_msg_type_number_t count = MACH_TASK_BASIC_INFO_COUNT;
  if (task_info(mach_task_self(), MACH_TASK_BASIC_INFO, (task_info_t)&info, &count) != KERN_SUCCESS)
    return 0;
  return (uint64_t)info.resident_size;
#else
  return 0;
#endif
}

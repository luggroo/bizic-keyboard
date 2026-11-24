// WASI SDK's single-threaded libc++ has no C++ exception unwinder. The
// dictionary is validated before release. Invalid resources raise a WebAssembly
// trap for the browser's loader instead of allowing lookup against partial data.
#include <cstddef>
#include <cstdlib>
extern "C" void* __cxa_allocate_exception(std::size_t n) noexcept { return std::malloc(n); }
extern "C" [[noreturn]] void __cxa_throw(void*, void*, void (*)(void*)) { __builtin_trap(); }

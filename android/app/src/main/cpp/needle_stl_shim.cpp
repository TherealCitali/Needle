// ---------------------------------------------------------------------------
// needle_stl_shim.cpp
//
// This file exists so CMake treats libneedlejni.so as a C++ target: the Needle
// engine archive (libneedle.a) is written in C++, so the final shared object has
// to be linked with the C++ driver and against libc++ (c++_shared).
//
// The bridge itself stays plain C (needle_jni.c); nothing here is called at
// runtime.
// ---------------------------------------------------------------------------

extern "C" int needle_jni_stl_shim() {
    return 0;
}

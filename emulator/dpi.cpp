#include "VSimTop__Dpi.h"
#include "dpi.h"
#include "emu.h"
#include <memory.h>

// C Env
extern "C" void halt(int reason) {
    switch (reason) {
        case HALT_EBREAK:
            emu->trap(TRAP_HALT_EBREAK, 0);
            break;
        default:
            emu->trap(TRAP_UNKNOWN, reason);
    }
}

extern "C" long long mem_read(int paddr) {
    long long res = pmem_read(paddr & (~0x3u));
    res |= ((long long)pmem_read(paddr + 4) << 32);
    return res;
}

extern "C" void mem_write(int paddr, const svBitVecVal* mask, long long data) {
    pmem_write(paddr & (~0x3u), data, *mask);
}
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

extern "C" int mem_read(int paddr) {
    return pmem_read(paddr & (~0x3u));
}

extern "C" void mem_write(int paddr, const svBitVecVal* mask, int data) {
    pmem_write(paddr & (~0x3u), data, *mask);
}
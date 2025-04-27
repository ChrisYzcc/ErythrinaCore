#include "VSimTop__Dpi.h"
#include "cosimulation.h"
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
    int res = pmem_read(paddr & (~0x3u));
    return res;
}

extern "C" void mem_write(int paddr, const svBitVecVal* mask, int data) {
    pmem_write(paddr & (~0x3u), data, *mask);
}

extern "C" svBit mem_req(int address, int id, svBit is_write) {
    if (dram == NULL) {
        assert(0);
    }
    if (dram->will_accept(address, is_write)) {
        auto req = new CoDRAMRequest();
        auto meta = new dramsim3_meta;
        req->address = address;
        req->is_write = is_write;
        meta->id = id;
        req->meta = meta;
        dram->add_request(req);
        return true;
    }
    return false;
}

extern "C" long long mem_rsp(svBit is_write) {
    if (dram == NULL) {
        assert(0);
    }
    auto rsp = is_write ? dram->check_write_response() : dram->check_read_response();
    if (rsp) {
        auto meta = static_cast<dramsim3_meta *>(rsp->req->meta);
        uint64_t response = meta->id | (1UL << 32);
        delete meta;
        delete rsp;
        return response;
    }
    return 0;
}
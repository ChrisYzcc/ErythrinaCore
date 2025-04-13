#ifndef __EMULATOR_H__
#define __EMULATOR_H__

#include <cstdint>
#include "VSimTop.h"
#include "verilated.h"

#define DUT_TOP VSimTop

typedef enum{
    EMU_RUN,
    EMU_ABORT_MEMORY_ERR,
    EMU_ABORT_TIMEOUT,
    EMU_HIT_GOOD,
    EMU_HIT_BAD,
}EmuState;

struct EmuArgs {
    uint64_t reset_cycles = 30;
    uint64_t max_cycles = -1;
    uint64_t max_inst = -1;

    bool dump_wave = false;
    bool enable_diff = true;
};

class Emulator {
private:
    DUT_TOP *dut_ptr;
    VerilatedFstC *tfp;
    EmuArgs args;
    EmuState state;
    uint64_t cycles;
    uint64_t inst_count;

    inline void reset_ncycles(size_t cycles);
    inline void single_cycle();

public:
    Emulator(int argc, const char *argv[]);
    ~Emulator();

    void run(uint64_t max_cycles, uint64_t max_inst);
};

#endif
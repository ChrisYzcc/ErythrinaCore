#ifndef __EMULATOR_H__
#define __EMULATOR_H__

#include <cstdint>
#include "VSimTop.h"
#include "isa.h"
#include "verilated.h"

#define DUT_TOP VSimTop

#define TIMEOUT_CYCLES 1500

#ifndef __SOC__
    #define PC_RSTVEC 0x80000000
#else
    #define PC_RSTVEC 0x30000000
#endif

typedef enum{
    EMU_RUN,
    EMU_HIT_GOOD,
    EMU_HIT_BAD,
    EMU_HIT_BREAK
}EmuState;

typedef enum{
    TRAP_MEM_ERR,
    TRAP_DIFF_ERR,
    TRAP_TIME_OUT,
    TRAP_HALT_EBREAK,
    TRAP_HALT_HIT_INSTR_BOUND,
    TRAP_HALT_HIT_CYCLE_BOUND,
    TRAP_UNKNOWN,
}TrapCode;

struct EmuArgs {
    uint64_t reset_cycles = 30;
    uint64_t max_cycles = -1;
    uint64_t max_inst = -1;

    char *image = nullptr;

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

    uint64_t nocmt_cycles;

    CPUState npc_arch_state;

    inline void reset_ncycles(size_t cycles);
    inline void single_cycle();

public:
    Emulator(int argc, const char *argv[]);
    ~Emulator();
    
    uint64_t pc_rstvec;

    void run();

    void trap(TrapCode trap_code, uint32_t trap_info);

    void diff_states(CPUState *ref);

    void get_npc_regfiles();

    int step();
};

extern Emulator *emu;

#endif
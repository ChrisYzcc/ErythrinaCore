#include "emu.h"
#include "VSimTop__Dpi.h"
#include "common.h"
#include "device.h"
#include "difftest.h"
#include "isa.h"
#include "lightsss.h"
#include "trace.h"
#include "svdpi.h"
#include "verilated.h"
#include "verilated_fst_c.h"
#include <cassert>
#include <getopt.h>
#include <cstddef>
#include <memory.h>
#include <csignal>
#include <iostream>

void handle_interrupt(int signum) {
    if (signum == SIGINT) {
        emu->trap(TRAP_SIG_INT, 0);
    }
}

EmuArgs parse_args(int argc, const char *argv[]) {
    EmuArgs args;

    const struct option table[] = {
        {"max-cycles", required_argument, NULL, 'c'},
        {"max-inst", required_argument, NULL, 'i'},
        {"dump-wave", no_argument, NULL, 'w'},
        {"dump-trace", no_argument, NULL, 't'},
        {"difftest", required_argument, NULL, 'd'},
        {"fork-debug", no_argument, NULL, 'f'},
        {0, 0, NULL, 0}
    };

    int opt;

    while ((opt = getopt_long(argc, (char *const *)argv, "-c:i:d:wtf", table, NULL)) != -1) {
        switch (opt) {
            case 'c':
                args.max_cycles = strtoull(optarg, NULL, 0);
                break;
            case 'i':
                args.max_inst = strtoull(optarg, NULL, 0);
                break;
            case 'w':
                args.dump_wave = true;
                break;
            case 't':
                args.dump_trace = true;
                break;
            case 'd':{
                args.enable_diff = true;
                diff_ref_so = optarg;
                break;
            }
            case 'f':{
                args.enable_fork = true;
                break;
            }
            case 1:{
                args.image = optarg;
                break;
            }
            default:
                printf("Usage: %s [OPTION...] IMAGE\n", argv[0]);
                printf("\t-c <max-cycles>         Run <max-cycles> cycles.\n");
                printf("\t-i <max-inst>           Run <max-inst> instructions.\n");
                printf("\t-w                      Dump waveform.\n");
                printf("\t-t                      Dump trace.\n");
                printf("\t-d <ref-so>             Enable diff.\n");
                exit(0);
        }
    }

    assert(!(args.enable_fork && args.dump_wave));
    return args;
}

Emulator::Emulator(int argc, const char *argv[])
    : cycles(0), state(EMU_RUN), inst_count(0), nocmt_cycles(0) {
    signal(SIGINT, handle_interrupt);
    
    args = parse_args(argc, argv);

    pc_rstvec = PC_RSTVEC;

    start_time = uptime();
    
    printf("===================== EMU =====================\n");

    // arch state
    memset(&npc_arch_sim_state, 0, sizeof(CPUState));
    memset(&npc_arch_real_state, 0, sizeof(CPUState));

    // context
    contx = new VerilatedContext;

    // dut
    dut_ptr = new DUT_TOP(contx);

    // device
    init_device();

    // wave
    if (args.dump_wave) {
        Verilated::traceEverOn(true);
        tfp = new VerilatedFstC;
        dut_ptr->trace(tfp, 99);
        tfp->open("waveform");
    }

    // lightSSS
    if (args.enable_fork) {
        printf("[Info] Enable fork debug\n");
        lightsss = new LightSSS;
    }

    // memory
    long img_size = init_mem(args.image);
    
    // difftest
    if (args.enable_diff) {
        printf("[Info] Enable difftest.\n");
        init_difftest(img_size, 1234);
    }

    // trace
    if (args.dump_trace) {
        printf("[Info] Enable trace dump.\n");
        trace_init();
    }

    printf("Start simulation...\n");

    // reset
    printf("Reset DUT...\n");
    reset_ncycles(args.reset_cycles);
    
}

Emulator::~Emulator() {
    printf("-----------------------------------------------\n");
    switch (state) {
        case EMU_HIT_GOOD:
            printf("Hit %sGood%s Trap\n", FontGreen, Restore);
            break;
        case EMU_HIT_BAD:
            printf("Hit %sBad%s Trap\n", FontRed, Restore);
            break;
        case EMU_HIT_BREAK:
            printf("Hit %sBreak%s Trap\n", FontBlue, Restore);
            break;
        case EMU_HIT_INTERRUPT:
            printf("Hit %sInterrupt%s Trap\n", FontYellow, Restore);
            break;
        default:
            printf("Hit %sUnknown%s Trap\n", FontRed, Restore);
            break;
    }

    if (args.dump_wave) {
        tfp->close();
        delete tfp;
    }

    if (args.enable_fork && !is_fork_child()) {
        bool need_wakeup = state == EMU_HIT_BAD;
        if (need_wakeup) {
            lightsss->wakeup_child(cycles);
        } else {
            lightsss->do_clear();
        }
        delete lightsss;
    }
    
    if (state == EMU_HIT_BAD) {
        status = 1;
    }

    if (args.dump_trace) {
        trace_dump();
    }

    dut_ptr->final();

    delete dut_ptr;
    delete dram;

    printf("===============================================\n");
    printf("Total Cycles: %ld, Total Instrs: %ld\nIPC: %.5lf\n",
        cycles, inst_count, (double)inst_count / cycles);
}

inline void Emulator::reset_ncycles(size_t cycles) {
    for (int i = 0; i < cycles; i++) {
        dut_ptr->reset = 1;
        dut_ptr->clock = 1;

        dut_ptr->eval();
        if (args.dump_wave) {
            tfp->dump(2 * i);
        }

        dut_ptr->clock = 0;
        dut_ptr->eval();
        if (args.dump_wave) {
            tfp->dump(2 * i + 1);
        }
    }
    dut_ptr->clock = 1;
    dut_ptr->eval();
    dut_ptr->reset = 0;
    dut_ptr->eval();
}

inline void Emulator::single_cycle() {
    if (contx->gotFinish()) {
        trap(TRAP_SIM_STOP, 0);
        return;
    }

    dram->tick();

    dut_ptr->clock = 1;
    dut_ptr->eval();

    if (args.dump_wave) {
        tfp->dump(2 * cycles + 2 * args.reset_cycles);
    }

    if (contx->gotFinish()) {
        trap(TRAP_SIM_STOP, 0);
        return;
    }

    dut_ptr->clock = 0;
    dut_ptr->eval();

    if (args.dump_wave) {
        tfp->dump(2 * cycles + 1 + 2 * args.reset_cycles);
    }

    cycles++;
}

void Emulator::trap(TrapCode trap_code, uint32_t trap_info) {
    switch (trap_code) {
        case TRAP_MEM_ERR: {
            printf("[Error] Memory access error at address: 0x%08x\n", trap_info);
            state = EMU_HIT_BAD;
            break;
        }
        case TRAP_TIME_OUT: {
            printf("[Error] Timeout error.\n");
            state = EMU_HIT_BAD;
            break;
        }
        case TRAP_HALT_EBREAK: {
            printf("[Info] Hit ebreak instruction.\n");
            get_npc_regfiles();
            if (npc_arch_real_state.gpr[get_regidx("a0")] == 0) {
                state = EMU_HIT_GOOD;
            }
            else {
                state = EMU_HIT_BAD;
            }
            break;
        }
        case TRAP_HALT_HIT_INSTR_BOUND: {
            printf("[Info] Hit instruction bound.\n");
            state = EMU_HIT_BREAK;
            break;
        }
        case TRAP_HALT_HIT_CYCLE_BOUND: {
            printf("[Info] Hit cycle bound.\n");
            state = EMU_HIT_BREAK;
            break;
        }
        case TRAP_DIFF_ERR: {
            printf("[Error] Difftest error.\n");
            printf("RAT:\n");
            for (int i = 0; i < ARCH_REG_NUM; i+=4) {
                printf("%s -> %02d, %s -> %02d, %s -> %02d, %s -> %02d\n",
                    get_regname(i), npc_uarch_state.rat[i],
                    get_regname(i+1), npc_uarch_state.rat[i+1],
                    get_regname(i+2), npc_uarch_state.rat[i+2],
                    get_regname(i+3), npc_uarch_state.rat[i+3]
                );
            }
            printf("RF:\n");
            for (int i = 0; i < PHY_REG_NUM; i+=4) {
                printf("[%02d] 0x%08x, [%02d] 0x%08x, [%02d]: 0x%08x, [%02d]: 0x%08x\n",
                    i, npc_uarch_state.phy_reg[i], i+1, npc_uarch_state.phy_reg[i+1],
                    i+2, npc_uarch_state.phy_reg[i+2], i+3, npc_uarch_state.phy_reg[i+3]);
            }
            state = EMU_HIT_BAD;
            break;
        }
        case TRAP_SIG_INT: {
            printf("[Info] Terminated by interrupt.\n");
            state = EMU_HIT_INTERRUPT;
            break;
        }
        case TRAP_SIM_STOP: {
            printf("[Info] Simulation stopped.\n");
            state = EMU_HIT_BAD;
            break;
        }
        default: {
            printf("[Error] Unknown trap code: %d, info: %d\n", trap_code, trap_info);
            state = EMU_HIT_BAD;
            break;
        }
    }
}

void Emulator::get_npc_regfiles() {
    svScope scope = svGetScopeFromName("TOP.SimTop.core.backend.rat.peeker");
    assert(scope);
    svSetScope(scope);

    // Get RAT from NPC
    for (int i = 0; i < ARCH_REG_NUM; i++) {
        set_arch_reg((svLogicVecVal *)&i);
        svLogicVecVal rat_val;
        get_phy_reg((svLogicVecVal *)&rat_val);
        npc_uarch_state.rat[i] = (uint32_t)rat_val.aval;
    }

    scope = svGetScopeFromName("TOP.SimTop.core.backend.regfile.peeker");
    assert(scope);
    svSetScope(scope);
    // Get RF from NPC
    for (int i = 0; i < PHY_REG_NUM; i++) {
        set_rf_idx((svLogicVecVal *)&i);
        svLogicVecVal rf_val;
        get_rf_value((svLogicVecVal *)&rf_val);
        npc_uarch_state.phy_reg[i] = (uint32_t)rf_val.aval;
    }

    // Generate NPC state
    for (int i = 0; i < ARCH_REG_NUM; i++) {
        npc_arch_real_state.gpr[i] = npc_uarch_state.phy_reg[npc_uarch_state.rat[i]];
    }
}

int Emulator::step() {
    single_cycle();

    if (state != EMU_RUN) {
        return 0;
    }

    // update NPC Sim state
    int cmt_cnt = 0;
    diff_infos infos;
    for (int i = 0; i < COMMIT_WIDTH; i++){
        if (get_diff_infos(&infos, i)) {
            cmt_cnt ++;
            npc_arch_sim_state.pc = infos.pc;
            npc_arch_real_state.pc = infos.pc;
            if (infos.rf_wen) {
                npc_arch_sim_state.gpr[infos.rf_waddr] = infos.rf_wdata;
            }

            if (args.dump_trace) {
                trace(infos.pc, infos.instr);
            }

            if (args.enable_diff) {
                if (infos.mem_en && is_device(infos.mem_addr) != -1) {
                    ref_difftest_regcpy(&npc_arch_sim_state, DIFFTEST_TO_REF);
                } else {
                    diff_step();
                    CPUState ref_arch_state;
                    ref_difftest_regcpy(&ref_arch_state, DIFFTEST_TO_DUT);
                    diff_states(&ref_arch_state, 1);
                }
            }
        }
    }

    if (args.enable_diff && cmt_cnt > 0) {
        get_npc_regfiles();
        CPUState ref_arch_state;
        ref_difftest_regcpy(&ref_arch_state, DIFFTEST_TO_DUT);
        diff_states(&ref_arch_state, 0);
    }

    if (cmt_cnt == 0) {
        nocmt_cycles++;
    }
    else {
        nocmt_cycles = 0;
    }

    if (nocmt_cycles > TIMEOUT_CYCLES) {
        trap(TRAP_TIME_OUT, 0);
    }

    return cmt_cnt;
}

void Emulator::diff_states(CPUState *ref, bool is_sim_arch) {
    CPUState *dut_state_ptr = nullptr;
    if (is_sim_arch) {
        dut_state_ptr = &npc_arch_sim_state;
    }
    else {
        dut_state_ptr = &npc_arch_real_state;
    }

    const char *sim_prefix = "SimArch";
    const char *real_prefix = "RealArch";
    const char *prefix;
    if (is_sim_arch) {
        prefix = sim_prefix;
    }
    else {
        prefix = real_prefix;
    }

    bool has_err = 0;
    // check regs
    for (int i = 0; i < ARCH_REG_NUM; i++) {
        if (dut_state_ptr->gpr[i] != ref->gpr[i]) {
            printf("[Error] %s Reg %s: NPC: 0x%08x, REF: 0x%08x\n", prefix, get_regname(i), dut_state_ptr->gpr[i], ref->gpr[i]);
            has_err = 1;
        }
    }

    // check pc
    if (dut_state_ptr->pc != ref->pc) {
        printf("[Error] %s PC: NPC: 0x%08x, REF: 0x%08x\n", prefix, dut_state_ptr->pc, ref->pc);
        has_err = 1;
    }
    else {
        if (has_err) {
            printf("[AT   ] %s PC: NPC: 0x%08x, REF: 0x%08x\n", prefix, dut_state_ptr->pc, ref->pc);
        }
    }

    if (has_err) {
        trap(TRAP_DIFF_ERR, 0);
    }
}

void Emulator::run() {
    printf("-----------------------------------------------\n");
    for (;;) {
        if (state != EMU_RUN) {
            break;
        }

        if (args.enable_fork) {
            static bool have_init_fork = false;
            uint32_t timer = uptime();
            if (((timer - lasttime_snapshot > args.fork_interval) || !have_init_fork) && !is_fork_child()) {
                have_init_fork = true;
                lasttime_snapshot = timer;
                switch (lightsss->do_fork()) {
                    case FORK_ERROR: assert(0);
                    case FORK_CHILD: fork_child_init();
                    default: break;
                }
            }
        }

        if (inst_count >= args.max_inst) {
            trap(TRAP_HALT_HIT_INSTR_BOUND, 0);
            break;
        }

        if (cycles >= args.max_cycles) {
            trap(TRAP_HALT_HIT_CYCLE_BOUND, 0);
            break;
        }

        inst_count += step();

        if (args.enable_fork && is_fork_child() && cycles != 0) {
            if (cycles == lightsss->get_end_cycles()) {
                printf("[Info] checkpoint has reached the main process abort point: %lu\n", cycles);
                trap(TRAP_HALT_HIT_CYCLE_BOUND, 0);
                break;
            }
        }
    }
}

void Emulator::fork_child_init() {
    printf("[Info] the oldest checkpoint start to dump wave ...\n");
    dut_ptr->atClone();

    Verilated::traceEverOn(true);
    tfp = new VerilatedFstC;
    dut_ptr->trace(tfp, 99);
    tfp->open("waveform");

    args.dump_wave = true;
    args.dump_trace = false;
}

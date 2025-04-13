#include "emu.h"
#include "verilated.h"
#include "verilated_fst_c.h"
#include <getopt.h>
#include <cstddef>

EmuArgs parse_args(int argc, const char *argv[]) {
    EmuArgs args;

    const struct option table[] = {
        {"max-cycles", no_argument, NULL, 'c'},
        {"max-inst", no_argument, NULL, 'i'},
        {"dump-wave", no_argument, NULL, 'd'},
        {"enable-diff", no_argument, NULL, 'e'},
    };

    int opt;

    while ((opt = getopt_long(argc, (char *const *)argv, "-ch:i:d:e", table, NULL)) != -1) {
        switch (opt) {
            case 'c':
                args.max_cycles = strtoull(optarg, NULL, 0);
                break;
            case 'i':
                args.max_inst = strtoull(optarg, NULL, 0);
                break;
            case 'd':
                args.dump_wave = true;
                break;
            case 'e':
                args.enable_diff = true;
                break;
            default:
                printf("Usage:\n");
                printf("\t-c <max-cycles>         Run <max-cycles> cycles.\n");
                printf("\t-i <max-inst>           Run <max-inst> instructions.\n");
                printf("\t-d                      Dump waveform.\n");
                printf("\t-e                      Enable diff.\n");
                exit(0);
        }
    }

    return args;
}

Emulator::Emulator(int argc, const char *argv[])
    : dut_ptr(new DUT_TOP), cycles(0), state(EMU_RUN), inst_count(0) {
    
    args = parse_args(argc, argv);

    // wave
    if (args.dump_wave) {
        printf("[Info] Enable waveform dump.\n");
        Verilated::traceEverOn(true);
        tfp = new VerilatedFstC;
        dut_ptr->trace(tfp, 99);
        tfp->open("waveform");
    }
}

Emulator::~Emulator() {
    if (args.dump_wave) {
        tfp->close();
        delete tfp;
    }
    delete dut_ptr;
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
    dut_ptr->reset = 0;
}

inline void Emulator::single_cycle() {
    dut_ptr->clock = 1;
    dut_ptr->eval();

    if (args.dump_wave) {
        tfp->dump(2 * cycles + 2 * args.reset_cycles);
    }

    dut_ptr->clock = 0;
    dut_ptr->eval();

    if (args.dump_wave) {
        tfp->dump(2 * cycles + 1 + 2 * args.reset_cycles);
    }

    cycles++;
}
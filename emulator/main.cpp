#include "emu.h"
Emulator *emu = nullptr;

int main(int argc, const char *argv[]) {
    emu = new Emulator(argc, argv);
    emu->run();
    delete emu;
    return 0;
}
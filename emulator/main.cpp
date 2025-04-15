#include "emu.h"
#include <cassert>
#include <cstdlib>
Emulator *emu = nullptr;
int status = 0;

int main(int argc, const char *argv[]) {
    emu = new Emulator(argc, argv);
    emu->run();
    delete emu;
    exit(status);
}
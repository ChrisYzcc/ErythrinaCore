# For Generating Verilator Package and Testing

VERILATOR = verilator
GTKWAVE = gtkwave

TOP_NAME = SimTop
OBJ_DIR = $(BUILD_DIR)/obj_dir/$(TOP_NAME)

# Emulator files
EMU_DIR = $(NPC_HOME)/emulator
EMU_CSRC = $(shell find $(EMU_DIR) -name "*.c" -or -name "*.cpp" -or -name "*.cc")

LLVM_CFLG = $(shell llvm-config-14 --cxxflags) -fPIE
LLVM_LFLG = $(shell llvm-config-14 --libs)

CFLG += -I$(EMU_DIR)/include ${LLVM_CFLG}
LFLG += ${LLVM_LFLG}
VFLG += --exe -cc --trace-fst -O3 --build -j 4 -CFLAGS "${CFLG}" -LDFLAGS "${LFLG}" --autoflush

ifeq ($(TOP_NAME), SimTop)
VSRCS ?= $(shell find $(abspath $(RTL_SIM_DIR)) -name "*.v" -or -name "*.sv")
$(VSRCS): $(SIM_VERILOG_SRC)
endif

verilate:
	mkdir -p $(OBJ_DIR)
	$(VERILATOR) $(VFLG) --Mdir $(OBJ_DIR) --top-module $(TOP_NAME) $(VSRCS) $(EMU_CSRC)

SIM_TARGET = $(OBJ_DIR)/V$(TOP_NAME)

sim:
	$(call git_commit, "sim RTL") # DO NOT REMOVE THIS LINE!!!
	$(SIM_TARGET) -d $(DIFF_SO) $(IMG) $(ARG) -t

wave:
	$(GTKWAVE) -r .gtkwaverc waveform

.PHONY: verilate
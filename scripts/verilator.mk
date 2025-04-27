# For Generating Verilator Package and Testing

VERILATOR = verilator
GTKWAVE = gtkwave

TOP_NAME = SimTop
OBJ_DIR = $(BUILD_DIR)/obj_dir/$(TOP_NAME)

# Emulator files
EMU_DIR = $(NPC_HOME)/emulator
EMU_CSRC = $(shell find $(EMU_DIR) -name "*.c" -or -name "*.cpp" -or -name "*.cc")

DRAM_SRC = $(shell find $(DRAMSIM_HOME)/src -name "*.o" -and ! -name "main.o")

LLVM_CFLG = $(shell llvm-config-14 --cxxflags) -fPIE
LLVM_LFLG = $(shell llvm-config-14 --libs)

CFLG += -I$(EMU_DIR)/include ${LLVM_CFLG} -I$(DRAMSIM_HOME)/src\
		-DDRAMSIM3_CONFIG=\\\"$(DRAMSIM_HOME)/configs/XiangShan.ini\\\" -DDRAMSIM3_OUTDIR=\\\"$(BUILD_DIR)\\\"
LFLG += ${LLVM_LFLG}
VFLG += --exe -cc --trace-fst -O3 --build -j 4 -CFLAGS "${CFLG}" -LDFLAGS "${LFLG}" --autoflush

ifeq ($(TOP_NAME), SimTop)
VSRCS ?= $(shell find $(abspath $(RTL_SIM_DIR)) -name "*.v" -or -name "*.sv")
$(VSRCS): $(SIM_VERILOG_SRC)
endif

verilate:
	mkdir -p $(OBJ_DIR)
	$(VERILATOR) $(VFLG) --Mdir $(OBJ_DIR) --top-module $(TOP_NAME) $(VSRCS) $(EMU_CSRC) $(DRAM_SRC)

SIM_TARGET = $(OBJ_DIR)/V$(TOP_NAME)

sim:
	$(call git_commit, "sim RTL") # DO NOT REMOVE THIS LINE!!!
	$(SIM_TARGET) -d $(DIFF_SO) $(IMG) $(ARG) 2> $(BUILD_DIR)/stderr.log

topdown:
	@python3 $(NPC_HOME)/scripts/topdown.py

wave:
	$(GTKWAVE) -r .gtkwaverc waveform

.PHONY: verilate
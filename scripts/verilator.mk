# For Generating Verilator Package and Testing

VERILATOR = verilator
GTKWAVE = gtkwave

TOP_NAME = SimTop
OBJ_DIR = $(BUILD_DIR)/obj_dir/$(TOP_NAME)

V_VFLG += -cc --trace-fst -O3 --build --savable

ifeq ($(TOP_NAME), SimTop)
VSRCS ?= $(shell find $(abspath $(RTL_SIM_DIR)) -name "*.v" -or -name "*.sv")
$(VSRCS): $(SIM_VERILOG_SRC)
endif

verilate:
	mkdir -p $(OBJ_DIR)
	$(VERILATOR) $(V_VFLG) --Mdir $(OBJ_DIR) --top-module $(TOP_NAME) $(VSRCS)

.PHONY: verilate
package utils

import chisel3._
import chisel3.util._

class PLRU(n: Int = 4) extends Module {
    val io = IO(new Bundle {
        val update = Flipped(ValidIO(UInt(log2Ceil(n).W)))
        val oldest = Output(UInt(log2Ceil(n).W))
    })

    val state = RegInit(0.U((n - 1).W))

    // 0: oldest is on the left
    // 1: oldest is on the right

    /* ---------------- update ---------------- */
    /**
      * @param tree_state
      * @param touch_way
      * @param tree_nways : managed ways in the tree
      */
    def get_nxt_state(tree_state:UInt, touch_way:UInt, tree_nways:Int): UInt = {
        if (tree_nways > 2) {
            val touch_left = !touch_way(log2Ceil(tree_nways) - 1)
            val left_tree_state = tree_state(tree_nways - 3, (tree_nways - 1) / 2)
            val right_tree_state = tree_state((tree_nways - 1) / 2 - 1, 0)
            val res = Cat(touch_left,
                Mux(touch_left,
                    get_nxt_state(left_tree_state, touch_way(log2Ceil(tree_nways) - 2, 0), tree_nways / 2),
                    left_tree_state),
                Mux(touch_left,
                    right_tree_state,
                    get_nxt_state(right_tree_state, touch_way(log2Ceil(tree_nways) - 2, 0), tree_nways / 2))
            )
            res
        }
        else {
            require(tree_nways == 2)
            !touch_way(0)
        }
    }

    when (io.update.valid) {
        state := get_nxt_state(state, io.update.bits, n)
    }

    /* ---------------- get ---------------- */

    def get_replace_way(state:UInt, tree_nways:Int): UInt = {
        if (tree_nways > 2) {
            val left_tree_state = state(tree_nways - 3, (tree_nways - 1) / 2)
            val right_tree_state = state((tree_nways - 1) / 2 - 1, 0)
            val left_way = get_replace_way(left_tree_state, tree_nways / 2)
            val right_way = get_replace_way(right_tree_state, tree_nways / 2)
            Cat(state(tree_nways - 2),
                Mux(state(tree_nways - 2),
                    get_replace_way(right_tree_state, tree_nways / 2),
                    get_replace_way(left_tree_state, tree_nways / 2))
            )
        }
        else {
            require(tree_nways == 2)
            state(0)
        }
    }
    io.oldest := get_replace_way(state, n)
}
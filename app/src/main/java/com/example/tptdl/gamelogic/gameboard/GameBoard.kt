package com.example.tptdl.gamelogic.gameboard

import com.example.tptdl.CellButton
import com.example.tptdl.gamelogic.MovementsCounter
import com.example.tptdl.gamelogic.Score
import com.example.tptdl.gamelogic.tokens.Void
import kotlinx.coroutines.*

// "internal" visibility modifier lets me access the class' parameters as long as it's from the same module
class GameBoard(internal val width : Int, internal val height : Int, private val ruleSet : RuleSet, private val score : Score, private val movementCounter : MovementsCounter) :
    java.util.Observable() {    // GameBoard probably shouldn't have Score or MovementsCounter in it, LevelActivity should

    private var myColumns : MutableList<Column> = mutableListOf()
    private var myRows : MutableList<Row> = mutableListOf()
    private var lastMovement = Movement(Pair(0,0), "Right")

    /* Right after the board has been initialized and shown, the controller should call checkForCombos()
       in case the board was generated with a combo already in it
     */
    init {
        for (i in 1..width) myColumns.add(Column(height, ruleSet.obtainBombRates()))
        for (i in 1..height) myRows.add(Row(width, ruleSet.obtainBombRates()))

        this.updateRows()

        GlobalScope.launch {
            this@GameBoard.checkForCombos(true)
            score.reset()
        }
    }

    fun printBoard() {

        val maxLenght = 10 //Strawberry
        val division = "-------------------------------------------------\n"
        var board : String = division

        for (i in 0 until height) {
            for (j in 0 until width) {
                val fruit = (((myColumns[j]).getCellAtIndex(i)).getCellValue()).toString()
                var spaces = ""
                for (k in 0 until maxLenght - fruit.length) spaces += " "

                board += " $fruit$spaces|"
            }
            board += "\n"
        }
        board += division
        println(board)
    } //TODO Eliminar

    /* Function will receive a Movement (obtained by view controllers) composed of the x and y
       coordinates of the cell to move, and a direction in which to move the selected cell. After
       this function is called, the controller will checkForCombos(), if none are found it will call
       undoLastMovement().
    */
    suspend fun doMovement(movement : Movement) {
        if (!movement.checkIfOutOfBounds(height, width))
            throw Exception("Invalid movement")

        val cellToSwitchCoords = movement.obtainCellCoords()
        val cellToSwitchWithCoords = movement.obtainCellToSwitch()
        val cellToSwitch = obtainCell(cellToSwitchCoords)
        val cellToSwitchWith = obtainCell(cellToSwitchWithCoords)

        switchCellValues(cellToSwitch, cellToSwitchWith)
        delay(500L)

        lastMovement = movement
        movementCounter.executeMovement()
        printBoard()
        //notifyObservers()
    }

    /* Switches values of 2 different Cell's (passed through parameters as a Pair of Int's,
     doesn't accept invalid switches)
     */
    fun switchCellValues(selectedCell: Cell, cellToSwitch: Cell) {
        selectedCell.switchValues(cellToSwitch)
    }

    // Switches the position of 2 cells in the GameBoard (Doesn't accept invalid switches).
    private fun switchCells(selectedCellCoords: Pair<Int, Int>, cellToSwitchCoords: Pair<Int, Int>) {
        val (xSelected, ySelected) = selectedCellCoords
        val (xToSwitch, yToSwitch) = cellToSwitchCoords
        val selectedCell = obtainCell(selectedCellCoords)
        val cellToSwitch = obtainCell(cellToSwitchCoords)

        if ((xSelected - xToSwitch) == 0) { // vertical switch
            myColumns[xSelected].switchCells(ySelected, yToSwitch)
            myRows[ySelected].setCellAtIndex(cellToSwitch, xSelected)
            myRows[yToSwitch].setCellAtIndex(selectedCell, xSelected)
        }
        else if (ySelected - yToSwitch == 0) { // horizontal switch
            myRows[ySelected].switchCells(xSelected, xToSwitch)
            myColumns[xSelected].setCellAtIndex(cellToSwitch, ySelected)
            myRows[yToSwitch].setCellAtIndex(selectedCell, ySelected)
        }
        else
            throw Exception("Invalid switch")
    } //TODO Repensar utilidad de este metodo @Alejo

    /* After the controller has called the function moveCell(), it will call checkForCombos() if
       checkForCombos finds any combos, it will execute them leaving Void in the spots where there
       was a combo. It will return false if no combos were found.
     */
    suspend fun checkForCombos(initializing : Boolean) : Boolean {
        val markedForRemoval : MutableList<Cell> = mutableListOf()
        val bombsToExplode : MutableList<Cell> = mutableListOf()

        for (i in 0 until width) {
            markedForRemoval.addAll(myColumns[i].getAllCombos())
        }
        for (i in 0 until height) {
            markedForRemoval.addAll(myRows[i].getAllCombos())
        }

        if (markedForRemoval.isEmpty()) return false

        bombsToExplode.addAll(this.checkForAdjacentExplosives(markedForRemoval))
        markedForRemoval.forEach { it.pop(score) }
        this.repopulateBoard(initializing)
        bombsToExplode.forEach {
            it.explode(this.getCellCoords(it), this)
        }
        this.repopulateBoard(initializing)
        return true // Have to put this to fulfill the boolean return but might not be correct
    }

    /* Function will go through every cell of the board, whenever it finds a cell with a Void value,
    it will replace it with a random token. After it's done, it'll call checkForCombos(), since the
    newly placed tokens may have left the board in a state where combos are available
     */
    private suspend fun repopulateBoard(initializing: Boolean) {
        if (!initializing) delay(500L)
        this.dropCurrentTokens()
        for (i in 0 until width) {
            if (!initializing) delay(50L)
            myColumns[i].refill()
        }
        if (!this.checkForCombos(initializing)) return
    }

    // Will vertically drop the current tokens as long as there is Void below
    private fun dropCurrentTokens() {
        for (i in 0 until width) { (myColumns[i]).shoveValuesToBottom() }
        // this.updateRows() //now unused, since values are switched instead of cells
        // updateView()?
    }

    private fun updateRows() {
        for (i in 0 until width) {
            for (j in 0 until height) {
                val cellToUpdate = myColumns[i].getCellAtIndex(j)
                myRows[j].setCellAtIndex(cellToUpdate, i)
            }
        }
    }

    /* In this case, the x axis represents the horizontal axis of the board (starting from the
       leftmost cell), y axis represents the vertical one (starting from the topmost cell).
       If the coordinates for the cell are out of bounds, function will return a Cell() with Void().
     */
    fun obtainCell(cellToObtain: Pair<Int, Int>): Cell {
        val (x, y) = cellToObtain
        if (x < 0 || y < 0 || x >= width || y >= height) { return Cell(Void()) }
        return myColumns[x].getCellAtIndex(y)
    }

    /* If the user believes no more combos are available, shuffle() should be called, and a new
    board will be created.
     */
    fun shuffle() {
        for (i in 0 until width) {
            myColumns[i].shuffle()
        }
    }

    /* Receives a list of cells, function will check for each cell individually if there's an explosive
       in any of it's four adjacent directions, if there is one it will add it to a list which is returned
       at the end of the function.
     */
    private fun checkForAdjacentExplosives(markedForRemoval: MutableList<Cell>): MutableList<Cell> {
        val bombsToExplode = mutableListOf<Cell>()
        markedForRemoval.forEach {
            val cellCoords = this.getCellCoords(it)
            val topCell = this.topCell(cellCoords)
            val bottomCell = this.bottomCell(cellCoords)
            val rightCell = this.rightCell(cellCoords)
            val leftCell = this.leftCell(cellCoords)
            if (topCell.isExplosive()) bombsToExplode.add(topCell)
            if (bottomCell.isExplosive()) bombsToExplode.add(bottomCell)
            if (rightCell.isExplosive()) bombsToExplode.add(rightCell)
            if (leftCell.isExplosive()) bombsToExplode.add(leftCell)
        }
        return bombsToExplode
    }

    // Obtains top cell of cell in parameter, if out of bounds, returns a Cell with Void
    private fun topCell(cellCoords: Pair<Int, Int>): Cell {
        val (x, y) = cellCoords
        if (y == 0) return Cell(Void())
        return obtainCell(Pair(x, y-1))
    }

    // Obtains bottom cell of cell in parameter, if out of bounds, returns a Cell with Void
    private fun bottomCell(cellCoords: Pair<Int, Int>): Cell {
        val (x, y) = cellCoords
        if (y == height-1) return Cell(Void())
        return obtainCell(Pair(x, y+1))
    }

    // Obtains right cell of cell in parameter, if out of bounds, returns a Cell with Void
    private fun rightCell(cellCoords: Pair<Int, Int>): Cell {
        val (x, y) = cellCoords
        if (y == width-1) return Cell(Void())
        return obtainCell(Pair(x+1, y))
    }

    // Obtains left cell of cell in parameter, if out of bounds, returns a Cell with Void
    private fun leftCell(cellCoords: Pair<Int, Int>): Cell {
        val (x, y) = cellCoords
        if (y == 0) return Cell(Void())
        return obtainCell(Pair(x-1, y))
    }

    // Precondition for this function is that the cell should be in the board, else it will return (-1, -1)
    private fun getCellCoords(cell: Cell): Pair<Int, Int> {
        for (i in 0 until width) {
            for (j in 0 until height) {
                if (cell.equals(myColumns[i].getCellAtIndex(j))) return Pair(i, j)
            }
        }
        return Pair(-1, -1)
    }

    // Undoes the last movement (does it again which causes the board to return to it's previous state)
    private suspend fun undoLastMovement() {
        this.doMovement(lastMovement)
        movementCounter.undoMovement()
    }

    fun getScore(): Score { return score }

    internal fun repopulateBoardTESTING() {  // internal function to test repopulateBoard()
        this.dropCurrentTokens()
        for (i in 0 until width) {
            myColumns[i].refill()
        }
        println("Board after repopulation:")
        this.printBoard()
        if (!this.checkForCombosTESTING()) return
    }

    internal fun checkForCombosTESTING() : Boolean  {   // internal function to test checkForCombos() that adds a print after removal of combo'ed cells
        val markedForRemoval : MutableList<Cell> = mutableListOf()
        val bombsToExplode : MutableList<Cell> = mutableListOf()

        for (i in 0 until width) {
            //println("THIS IS IN COMBOS: " + (myColumns[i].getAllCombos().toString()))
            //println("stop")
            markedForRemoval.addAll(myColumns[i].getAllCombos())
        }
        for (i in 0 until height) {
            //println("THIS IS IN COMBOS: " + (myRows[i].getAllCombos().toString()))
            //println("stop")
            markedForRemoval.addAll(myRows[i].getAllCombos())
        }

        //println("SHOULD HAVE ALL COMBOS" + markedForRemoval.toString())
        if (markedForRemoval.isEmpty()) return false

        bombsToExplode.addAll(this.checkForAdjacentExplosives(markedForRemoval))
        markedForRemoval.forEach { it.pop(score) }
        println("Board after combos identified and emptied:")
        this.printBoard()
        bombsToExplode.forEach {
            it.explode(this.getCellCoords(it), this)
        }
        println("Board after bombs are exploded:")
        this.printBoard()
        this.repopulateBoardTESTING()
        return true
    }

    internal fun setCellTESTING(cellCoords : Pair<Int, Int>, cell : Cell) {    // forcefully sets a cell, for testing purposes only
        val (x, y) = cellCoords
        myColumns[x].setCellAtIndex(cell, y)
        myRows[y].setCellAtIndex(cell, x)
    }

    fun linkObservers(buttonList: MutableList<MutableList<CellButton>>) {
        for (row in 0 until buttonList.size) {

            for (col in 0 until buttonList[row].size) {
                val cell = myRows[row].getCellAtIndex(col)
                val button = buttonList[row][col]
                cell.addObserver(button)
                button.setCell(cell)

            }
        }
    }

    private fun getAdjacents(cell : Cell): MutableList<Cell> {
        val cellCoords = getCellCoords(cell)
        return mutableListOf(topCell(cellCoords), bottomCell(cellCoords), rightCell(cellCoords), leftCell(cellCoords))
    }

    private fun isAdjacent(cell1 : Cell, cell2 : Cell) : Boolean {
        val adjacentList = getAdjacents(cell1)
        return adjacentList.contains(cell2)
    }

    private fun getDirection(cell1 : Cell, cell2 : Cell) : String {
        val adjacentList = getAdjacents(cell1)
        val stringsList = mutableListOf("Up", "Down", "Right", "Left")
        for (i in 0 until adjacentList.size) {
            if (adjacentList[i] == cell2) {
                println(stringsList[i])
                return stringsList[i]
            }
        }
        println("NotValid")
        return "NotValid"
    }

    fun tryMovement(cell1 : Cell, cell2 : Cell) : Boolean {
        if(!isAdjacent(cell1, cell2)) return false

        val direction = getDirection(cell1, cell2)
        val movement = Movement(getCellCoords(cell1), direction)

        runBlocking {
            doMovement(movement)
            if (!checkForCombos(false)) undoLastMovement()
        }

        println("END OF MOVEMENT")
        println("PUNTOS ACTUALES: " + score.currentPoints)
        return true
    }
}

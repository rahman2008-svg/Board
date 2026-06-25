package com.example.data.database

import kotlinx.coroutines.flow.Flow

class BoardRepository(private val boardDao: BoardDao) {
    val allBoards: Flow<List<BoardEntity>> = boardDao.getAllBoards()
    
    fun getBoardsByFolder(folder: String): Flow<List<BoardEntity>> {
        return boardDao.getBoardsByFolder(folder)
    }
    
    suspend fun getBoardById(id: Long): BoardEntity? {
        return boardDao.getBoardById(id)
    }
    
    suspend fun insertBoard(board: BoardEntity): Long {
        return boardDao.insertBoard(board)
    }
    
    suspend fun deleteBoardById(id: Long) {
        boardDao.deleteBoardById(id)
    }
    
    suspend fun updateBoardMetadata(id: Long, name: String, folder: String) {
        boardDao.updateBoardMetadata(id, name, folder, System.currentTimeMillis())
    }
    
    suspend fun updateBoardContent(id: Long, contentJson: String) {
        boardDao.updateBoardContent(id, contentJson, System.currentTimeMillis())
    }
    
    suspend fun updateBoardLockState(id: Long, isLocked: Boolean) {
        boardDao.updateBoardLockState(id, isLocked, System.currentTimeMillis())
    }
}

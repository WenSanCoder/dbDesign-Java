package com.zjut.edusystem.selection;

public interface SelectionQueueService {
    String acceptSelectionRequest(Long studentId, Long teachingClassId, Long roundId);
}

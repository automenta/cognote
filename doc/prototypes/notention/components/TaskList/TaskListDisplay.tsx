import React, {useCallback} from 'react';
import TaskListItem from './TaskListItem';
import {Note} from '../../types';
import styles from './TaskList.module.css';
import {DragDropContext, Droppable, Draggable} from 'react-beautiful-dnd';

interface TaskListDisplayProps {
    tasks: Note[];
    selectedId: string | null;
    onTaskSelect: (id: string) => void;
    onPriorityChange: (id: string, priority: number) => void;
}

export const TaskListDisplay: React.FC<TaskListDisplayProps> = ({
                                                                    tasks,
                                                                    selectedId,
                                                                    onTaskSelect,
                                                                    onPriorityChange,
                                                                }) => {
    const handleChangePriority = useCallback((id: string, priority: number) => {
        onPriorityChange(id, priority);
    }, [onPriorityChange]);

    const handleSelectTask = useCallback((id: string) => {
        onTaskSelect(id);
    }, [onTaskSelect]);

    const handleOnDragEnd = (result: any) => {
        console.log('drag ended', result);
    };

    return (
        <DragDropContext onDragEnd={handleOnDragEnd}>
            <Droppable droppableId="tasks">
                {(provided) => (
                    <div
                        className={styles.taskListItems}
                        {...provided.droppableProps}
                        ref={provided.innerRef}
                    >
                        {tasks.map((task, index) => (
                            <Draggable key={task.id} draggableId={task.id} index={index}>
                                {(provided) => (
                                    <div
                                        ref={provided.innerRef}
                                        {...provided.draggableProps}
                                        {...provided.dragHandleProps}
                                    >
                                        <TaskListItem
                                            task={task}
                                            onPriorityChange={handleChangePriority}
                                            onClick={handleSelectTask}
                                            isSelected={task.id === selectedId}
                                        />
                                    </div>
                                )}
                            </Draggable>
                        ))}
                        {provided.placeholder}
                    </div>
                )}
            </Droppable>
        </DragDropContext>
    );
};

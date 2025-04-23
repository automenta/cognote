import {v4 as uuidv4} from 'uuid';

class IdService {
    generateId(): string {
        return uuidv4();
    }
}

const idService = new IdService();
export default idService;

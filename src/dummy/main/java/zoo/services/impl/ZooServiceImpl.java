package zoo.services.impl;

import zoo.services.ZooService;

public final class ZooServiceImpl implements ZooService {
    @Override
    public String message() {
        return "service-ok";
    }
}

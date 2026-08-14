module zoo.dummy {
    requires jdk.unsupported;

    exports zoo;
    exports zoo.services;

    uses zoo.services.ZooService;
    provides zoo.services.ZooService with zoo.services.impl.ZooServiceImpl;
}

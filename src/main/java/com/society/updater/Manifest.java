package com.society.updater;

import java.util.ArrayList;
import java.util.List;

public class Manifest {
    public String version = "";
    public String name = "";
    public String changelog = "";
    public List<String> ignore = new ArrayList<>();
    public List<String> delete = new ArrayList<>();
    public List<ManifestFile> files = new ArrayList<>();
}

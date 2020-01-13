class Project:
    """Wrapper class for supported projects. Seems to be mostly
    useful for generating stats."""

    PROJECTS = ["viser", "rccsi", "pause"]

    @staticmethod
    def isSupportedProject(project):
        if project in Project.PROJECTS:
            return True
        return False

from task.buildtask import BuildTask
from task.cleantask import CleanTask
from task.copytask import CopyTask
from task.emailtask import EmailTask
from task.resulttask import ResultTask
from task.runtask import RunTask
from task.synctask import SyncTask


class Tasks:
    """This class controls running all the tasks specified in the
    experiment."""

    def runAllTasks(self, options):
        """Run all the tasks. The order is pre-determined and important."""

        self.tasksTuple = options.getTasksTuple()
        if "clean" in self.tasksTuple or "all" in self.tasksTuple:
            CleanTask.cleanTask(options)

        if "sync" in self.tasksTuple or "all" in self.tasksTuple:
            SyncTask.syncTask(options)

        if "build" in self.tasksTuple or "all" in self.tasksTuple:
            BuildTask.buildTask(options)

        if "run" in self.tasksTuple or "all" in self.tasksTuple:
            RunTask.runTask(options)

        if "result" in self.tasksTuple or "all" in self.tasksTuple:
            ResultTask.resultTask(options)

        if "copy" in self.tasksTuple:
            CopyTask.copyTask(options)

        if "email" in self.tasksTuple or "all" in self.tasksTuple:
            EmailTask.emailTask(options)

package sirin.desktop

import java.nio.file.Path
import java.util.UUID
import sirin.*
import sirin.specimen.*

/** Real application entry. Filesystem access stays in repository/import Work adapters. */
object PersistentWorkbench:
  def main(args: Array[String]): Unit =
    val directory=args.toVector match
      case Vector("--documents",path) => Path.of(path)
      case Vector() | Vector("interactive") => Path.of(System.getProperty("user.home"),".sirin-alpha","documents")
      case _ => throw new IllegalArgumentException("Usage: PersistentWorkbench [--documents DIRECTORY]")
    val repository=new FileDocumentRepository(directory)
    val importer=new FileDocumentImporter(repository)
    Application.run { (scope: Scope,services: ApplicationServices) =>
      new DocumentWorkspace(scope,repository,importer,services,() => DocumentId(UUID.randomUUID().toString)).windows
    }
